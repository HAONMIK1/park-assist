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

기능은 한 바퀴 돈다.

- 핀맵, 타이밍 상수
- 순차 발사 스케줄러 (인터럽트 기반 에코 측정, `delay()` 없음)
- XOR 체크섬, 패킷 조립/파싱
- 사각지대 진입 시 최고 경보 유지
- **연속 경보 곡선** — 거리에 따라 울림 간격이 1000ms → 80ms로 등비 변화,
  강도 0.93 이상은 연속음
- **BLE** — 이름·서비스 UUID 광고, TX notify(CCCD 포함), RX write 콜백,
  연결 끊긴 뒤 광고 재개
- **NVS** — 거리 기준과 전원 상태를 저장/복원. 값이 실제로 바뀔 때만 쓴다

아직 없는 것:

- [ ] 표준 Battery Service(`0x180F`) — 전원 방식이 정해져야 잔량을 잴 수 있다.
      없으면 앱이 "알 수 없음(`--`)"으로 두고 나머지는 정상 동작한다.
- [ ] 연결 간격 조정 (코드 내 `TODO`) — 실측에서 패킷이 뭉쳐 도착하면 조인다.

> ⚠️ **컴파일·실측 검증되지 않았다.** 작업 환경에서 arduino-cli 다운로드가 네트워크
> 정책에 막혀 빌드를 돌려보지 못했다. 첫 빌드 때 아래 "빌드 환경"을 먼저 확인할 것.

## 빌드

보드: **ESP32 Dev Module**

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 .
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 .
```

### 빌드 환경

BLE 코드는 **arduino-esp32 코어에 내장된 Bluedroid 기반 `BLEDevice` 라이브러리**
(`BLEDevice.h` / `BLE2902.h`)를 쓴다. 코어 2.x / 3.0~3.2 계열의 API를 기준으로 작성했다.

컴파일이 깨진다면 이 세 곳을 먼저 본다 — 코어 버전에 따라 시그니처가 달라진 이력이 있다.

| 위치 | 확인할 것 |
| --- | --- |
| `RxCallbacks::onWrite` | 3.x는 `(BLECharacteristic*, esp_ble_gatts_cb_param_t*)` 오버로드가 추가됐다. 1-인자 형태가 없으면 그쪽으로 바꾼다 |
| `g_txChar->addDescriptor(new BLE2902())` | CCCD를 자동 생성하는 버전이면 중복이 된다. 그때는 이 줄을 뺀다 |
| `advertisementData.setName(...)` | `std::string` / `String` 중 어느 쪽을 받는지 |

NimBLE로 갈아탈 경우 CCCD가 자동 생성되므로 `BLE2902` 관련 줄을 들어내야 한다.

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
