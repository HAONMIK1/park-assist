# CLAUDE.md

이 저장소에서 작업할 때 참고하는 지침이다.

## 프로젝트 개요

ESP32 기반 **자동차 집게형 주차 센서**. 차량 뒤 번호판 등에 집게로 물려 장착하는
후방 4채널 초음파 주차 보조 장치이다.

- **펌웨어** (Arduino / C++) — `firmware/park-assist-fw/`
- **안드로이드 앱** (Kotlin / Jetpack Compose) — `android/ParkAssist/`

두 산출물을 한 저장소에서 관리하는 **모노레포**다. 펌웨어와 앱은 BLE 패킷 규격으로만
결합되어 있으므로, 규격을 바꾸면 **반드시 양쪽을 같은 커밋/PR에서 함께** 고친다.

## 저장소 구조

```
park-assist/
├── docs/
│   ├── ble-protocol.md      # BLE 패킷 규격 (단일 진실 공급원)
│   ├── hardware.md          # 부품, 배선, 전원
│   └── decisions/           # ADR (설계 결정 기록)
├── firmware/park-assist-fw/ # Arduino 스케치 (폴더명 = .ino 파일명)
└── android/ParkAssist/      # Android Studio 프로젝트
```

## BLE 규격

**규격의 단일 진실 공급원은 [`docs/ble-protocol.md`](docs/ble-protocol.md)다.**
UUID·패킷 레이아웃·체크섬을 확인하거나 수정할 일이 있으면 코드에서 유추하지 말고
반드시 그 문서를 먼저 읽는다. 규격을 변경하면 문서 → 펌웨어 → 앱 순서로 함께 고친다.

요약 (자세한 내용은 위 문서 참조):

| 항목 | 값 |
| --- | --- |
| 기기 이름 | `PARK-01` |
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX (notify, 기기→폰) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (write, 폰→기기) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| 수신 | `[0xAA][d1][d2][d3][d4][XOR]` 6바이트, 10Hz |
| 송신 | `[0xBB][near][mid][far][XOR]` 거리 기준 변경 |
| 송신 | `[0xCC][0x00\|0x01][XOR]` 전원 대기/작동 |

XOR = 마지막 바이트를 제외한 전체 바이트의 XOR.

## 하드웨어

| 구분 | 부품 |
| --- | --- |
| MCU | ESP32 DevKitC 30핀 |
| 거리 센서 | JSN-SR04T 방수 초음파 × 4 |
| 경보 | 액티브 부저 HW-508 |

### 핀맵

| 채널 | Trig | Echo |
| --- | --- | --- |
| CH1 | GPIO 13 | GPIO 34 |
| CH2 | GPIO 25 | GPIO 35 |
| CH3 | GPIO 26 | GPIO 36 (VP) |
| CH4 | GPIO 27 | GPIO 39 (VN) |
| 부저 | GPIO 4 | — |

- Echo에 배정된 **GPIO 34/35/36/39는 입력 전용**이다. 출력으로 쓰거나 내부 풀업을
  기대하는 코드를 넣지 말 것.
- 채널 ↔ 패킷 매핑: `d1=CH1 … d4=CH4`. 물리 배치는 차량 뒤에서 봤을 때 좌 → 우 순서.

자세한 배선·전원·주의사항은 [`docs/hardware.md`](docs/hardware.md) 참조.

## 개발 원칙

1. **부저는 ESP32 직결. 폰/BLE 없이도 반드시 작동해야 한다 (안전 최우선).**
   경보 로직은 BLE 연결 상태, 앱 실행 여부, 폰의 존재 여부와 **완전히 독립**이어야 한다.
   부저를 울리는 경로에 BLE 콜백·연결 확인·앱에서 받은 상태가 끼어들면 안 된다.
2. **센서는 순차 발사.** 동시에 쏘면 상호간섭으로 값이 오염된다. 한 채널의 에코를
   받거나 타임아웃될 때까지 다음 채널을 발사하지 않는다.
3. **`loop()`에서 `delay()` 금지.** `millis()` / `micros()` 기반 논블로킹으로 작성한다.
   에코 대기도 블로킹 `pulseIn()` 대신 인터럽트 + 타임아웃으로 처리한다.
4. **앱은 보조 표시 장치다. 주 경고 채널이 아니다.**
   앱에만 있고 기기에는 없는 경고 기능을 만들지 않는다. 앱이 죽어도 안전 기능은
   그대로 동작해야 한다.

## 빌드

### 펌웨어

Arduino IDE 또는 arduino-cli. 보드: **ESP32 Dev Module**.
스케치 폴더명과 `.ino` 파일명은 반드시 같아야 하므로 `park-assist-fw` 이름을 바꾸지 말 것.

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/park-assist-fw
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 firmware/park-assist-fw
```

### 안드로이드

```bash
cd android/ParkAssist
./gradlew assembleDebug
./gradlew test
```

## 안드로이드 작업 시 주의

- **CCCD 필수.** `setCharacteristicNotification()`만 호출하면 notify가 오지 않는다.
  CCCD 디스크립터 `00002902-0000-1000-8000-00805f9b34fb`에
  `ENABLE_NOTIFICATION_VALUE`를 write해야 한다.
- **권한 분기.** Android 12(API 31)+ 는 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`,
  11 이하는 `ACCESS_FINE_LOCATION`.
- **`onCharacteristicChanged` 오버로드 2개 모두 구현.** API 33에서 시그니처가 바뀌었다.
  구버전(값을 `characteristic.value`로 읽음)과 신버전(`ByteArray` 파라미터) 양쪽 필요.
- **체크섬 검증 후 사용.** XOR이 안 맞는 패킷은 조용히 버린다. 부분 갱신 금지.
- **목업 모드를 깨뜨리지 말 것.** `BleRepository`는 인터페이스이고 Real/Mock 두 구현이
  있다. 실기기 없이 UI를 테스트하는 유일한 수단이므로 Real 전용 타입을 UI 레이어로
  누출시키지 않는다.
- **대상 사용자는 60대다.** 글자·버튼을 크게, 정보 밀도는 낮게 유지한다.

## 설계 결정

되돌리기 어렵거나 나중에 "왜 이렇게 했지?"가 될 결정은 `docs/decisions/`에 ADR로
기록한다. 작성법은 [`docs/decisions/README.md`](docs/decisions/README.md) 참조.
