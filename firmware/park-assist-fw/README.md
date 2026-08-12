# park-assist-fw

ESP32 펌웨어. Arduino 스케치이므로 **폴더명과 `.ino` 파일명이 같아야 한다.**
`park-assist-fw` 이름을 바꾸지 말 것.

## 유닛 두 대, 펌웨어 한 벌

앞뒤 범퍼에 유닛을 하나씩 물린다([ADR 0004](../../docs/decisions/0004-two-device-split.md)).
회로와 핀맵이 완전히 같고, 굽기 전에 `.ino` 상단의 매크로만 바꾼다.

```c
#define ZONE_IS_REAR 1   // 후방 → PARK-01
#define ZONE_IS_REAR 0   // 전방 → PARK-02
```

두 유닛은 서비스·캐릭터리스틱 UUID가 같고 광고 이름만 다르다.
**광고 패킷(또는 스캔 응답)에 기기 이름을 반드시 실어야 한다.** 앱이 이름으로 앞뒤를
가려내기 때문에, 이름이 없으면 두 연결이 같은 기기를 잡는다.

## 현재 상태

**스켈레톤.** 다음이 잡혀 있다.

- 핀맵, 타이밍 상수
- 순차 발사 스케줄러 (인터럽트 기반 에코 측정, `delay()` 없음)
- XOR 체크섬, 패킷 조립/파싱
- 사각지대 진입 시 최고 경보 유지
- **연속 경보 곡선** — 거리에 따라 울림 간격이 1000ms → 80ms로 등비 변화,
  강도 0.93 이상은 연속음

아직 없는 것 (코드 내 `TODO`):

- [ ] BLE 초기화 — `DEVICE_NAME` 광고, 서비스/캐릭터리스틱 등록
- [ ] TX notify 실제 전송
- [ ] RX write 콜백 연결 (`bleHandleCommand()`는 구현되어 있음)
- [ ] NVS 설정 저장/복원
- [ ] 표준 Battery Service(`0x180F`) — 선택 사항. 없으면 앱이 "알 수 없음"으로 둔다.

**실기기에서 컴파일·실측 검증되지 않았다.**

## 빌드

보드: **ESP32 Dev Module**

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 .
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 .
```

## 수정 시 지켜야 할 것

- `loop()`에 `delay()`를 넣지 않는다. Trig 펄스용 `delayMicroseconds(10)`은 예외.
- 부저 경로에 BLE 연결 상태를 조건으로 넣지 않는다
  ([ADR 0001](../../docs/decisions/0001-buzzer-direct-drive.md)).
- 두 채널을 동시에 발사하지 않는다
  ([ADR 0002](../../docs/decisions/0002-sequential-sensor-firing.md)).
- **경보 곡선(`alarmIntensity` / `beepIntervalMs`)은 앱의 `AlarmCurve.kt`와 같은 모양이어야
  한다.** 한쪽만 바꾸면 기기 부저와 앱 소리가 어긋난다. 기준은
  [`docs/ble-protocol.md`](../../docs/ble-protocol.md) 6절.
- 패킷 규격을 바꾸면 문서와 앱을 같은 PR에서 함께 고친다.
