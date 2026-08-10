# park-assist-fw

ESP32 펌웨어. Arduino 스케치이므로 **폴더명과 `.ino` 파일명이 같아야 한다.**
`park-assist-fw` 이름을 바꾸지 말 것.

## 현재 상태

**스켈레톤.** 다음이 잡혀 있다.

- 핀맵, 타이밍 상수
- 순차 발사 스케줄러 (인터럽트 기반 에코 측정, `delay()` 없음)
- XOR 체크섬, 패킷 조립/파싱
- 부저 경보 단계 판정 (BLE와 독립)
- 사각지대 진입 시 최고 경보 유지

아직 없는 것 (코드 내 `TODO`):

- [ ] BLE 초기화 — `PARK-01` 광고, 서비스/캐릭터리스틱 등록
- [ ] TX notify 실제 전송
- [ ] RX write 콜백 연결 (`bleHandleCommand()`는 구현되어 있음)
- [ ] NVS 설정 저장/복원

**실기기에서 컴파일·실측 검증되지 않았다.**

## 빌드

보드: **ESP32 Dev Module**

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 .
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 .
```

## 수정 시 지켜야 할 것

- `loop()`에 `delay()`를 넣지 않는다. Trig 펄스용 `delayMicroseconds(10)`은 예외.
- 부저 경로에 BLE 연결 상태를 조건으로 넣지 않는다 ([ADR 0001](../../docs/decisions/0001-buzzer-direct-drive.md)).
- 두 채널을 동시에 발사하지 않는다 ([ADR 0002](../../docs/decisions/0002-sequential-sensor-firing.md)).
- 패킷 규격을 바꾸면 [`docs/ble-protocol.md`](../../docs/ble-protocol.md)와 앱을
  같은 PR에서 함께 고친다.
