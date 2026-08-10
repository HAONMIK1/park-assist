# park-assist

ESP32 기반 **자동차 집게형 후방 주차 센서**.

차량 뒤쪽에 집게로 물려 장착하는 4채널 초음파 주차 보조 장치다. 기기 자체의 부저로
경보를 울리고, 안드로이드 앱은 BLE로 연결해 각 방향의 거리를 눈으로 확인하는
**보조 표시 장치** 역할을 한다.

> **앱은 없어도 된다.** 부저 경보는 폰·BLE와 무관하게 기기 단독으로 동작한다.
> 자세한 이유는 [ADR 0001](docs/decisions/0001-buzzer-direct-drive.md) 참조.

## 구성

```
park-assist/
├── docs/
│   ├── ble-protocol.md      # BLE 패킷 규격 (단일 진실 공급원)
│   ├── hardware.md          # 부품, 배선, 전원, 조립 체크리스트
│   └── decisions/           # 설계 결정 기록 (ADR)
├── firmware/park-assist-fw/ # ESP32 펌웨어 (Arduino / C++)
└── android/ParkAssist/      # 안드로이드 앱 (Kotlin / Jetpack Compose)
```

## 하드웨어

| 구분 | 부품 | 수량 |
| --- | --- | --- |
| MCU | ESP32 DevKitC 30핀 | 1 |
| 거리 센서 | JSN-SR04T 방수 초음파 모듈 | 4 |
| 경보 | 액티브 부저 HW-508 | 1 |
| 전원 | 미정 (차량 12V 벅 컨버터 / 18650 + TP4056) | 1 |
| 기구 | 집게형 마운트 | 1 |

### 핀맵

| 채널 | Trig | Echo |
| --- | --- | --- |
| CH1 (좌측 끝) | GPIO 13 | GPIO 34 |
| CH2 | GPIO 25 | GPIO 35 |
| CH3 | GPIO 26 | GPIO 36 (VP) |
| CH4 (우측 끝) | GPIO 27 | GPIO 39 (VN) |
| 부저 | GPIO 4 | — |

배선 주의사항(Echo 5V 레벨, 입력 전용 핀, 부저 구동 전류)은
[`docs/hardware.md`](docs/hardware.md)에 정리되어 있다. **조립 전에 반드시 읽을 것.**

## 빌드

### 펌웨어

준비물: [arduino-cli](https://arduino.github.io/arduino-cli/) 또는 Arduino IDE.

```bash
# 최초 1회 — ESP32 보드 지원 설치
arduino-cli core update-index --additional-urls \
  https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
arduino-cli core install esp32:esp32

# 빌드
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/park-assist-fw

# 업로드 (포트는 환경에 맞게)
arduino-cli upload --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 firmware/park-assist-fw
```

보드 설정: **ESP32 Dev Module**, Flash 4MB, 업로드 속도 921600.

### 안드로이드 앱

준비물: Android Studio (JDK 17), minSdk 26 / targetSdk 34.

```bash
cd android/ParkAssist
./gradlew assembleDebug        # 디버그 APK 빌드
./gradlew installDebug         # 연결된 기기에 설치
./gradlew test                 # 단위 테스트
```

실기기 없이 UI를 확인하려면 앱 설정 화면에서 **목업 모드**를 켠다. 가짜 거리 데이터가
생성되어 BLE 기기 없이도 전체 화면을 테스트할 수 있다.

## BLE 규격

기기 이름 `PARK-01`, Nordic UART 계열 UUID를 쓰는 고정 길이 바이너리 패킷.
전체 규격은 [`docs/ble-protocol.md`](docs/ble-protocol.md)에 있다.

| 방향 | 패킷 | 용도 |
| --- | --- | --- |
| 기기 → 폰 | `[0xAA][d1][d2][d3][d4][XOR]` | 4채널 거리 (cm), 10Hz |
| 폰 → 기기 | `[0xBB][near][mid][far][XOR]` | 경보 거리 기준 변경 |
| 폰 → 기기 | `[0xCC][0x00\|0x01][XOR]` | 전원 대기 / 작동 |

## 개발

작업 규칙, 개발 원칙, 자주 쓰는 명령은 [`CLAUDE.md`](CLAUDE.md)에 있다.

핵심 원칙 네 가지:

1. 부저는 ESP32 직결 — 폰/BLE 없이도 작동한다 (안전 최우선)
2. 센서는 순차 발사 — 동시 발사 시 상호간섭
3. `loop()`에서 `delay()` 금지 — `millis()` 기반 논블로킹
4. 앱은 보조 표시 장치 — 주 경고 채널이 아니다

## 상태

| 영역 | 상태 |
| --- | --- |
| BLE 규격 | 확정 (배터리 잔량 전달 방식은 미정) |
| 하드웨어 | 부품 확정, 전원 방식 미정 |
| 펌웨어 | 스켈레톤 |
| 안드로이드 앱 | 설계 중 |
