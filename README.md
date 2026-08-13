# park-assist

ESP32 기반 **자동차 집게형 주차/주행 보조 센서**.

앞뒤 번호판에 집게로 물려 장착하는 초음파 거리 경보 장치다. 기기 자체의 부저로 경보를
울리고, 안드로이드 앱은 BLE로 연결해 방향과 거리를 눈으로 보여주는 **보조 표시 장치**다.

> **앱은 없어도 된다.** 부저 경보는 폰·BLE와 무관하게 기기 단독으로 동작한다.
> 자세한 이유는 [ADR 0001](docs/decisions/0001-buzzer-direct-drive.md) 참조.

## 두 가지 모드

| | 주차 | 주행 |
| --- | --- | --- |
| 경보 기준 | 거리 | **접근 속도** |
| 소리 | 가까울수록 촘촘하고 크고 높게 (연속 곡선) | 급접근일 때만, 처음부터 최대 강도 |
| 쓰임 | 후진 주차, 좁은 골목 | 정체 구간에서 앞뒤 차 급접근 |

주행 중에는 앞뒤 차가 1~2m에 있는 게 정상이라 거리만으로는 경보를 낼 수 없다. 그래서
최근 0.6초의 접근 속도를 보고 판정한다. 티맵 같은 내비게이션을 쓰는 중에도 들리도록
오디오 포커스를 잠깐 가져간다.

> ⚠️ **주행 감지에는 한계가 있다.** 초음파 센서 측정 한계가 약 250cm, 갱신이 10Hz라
> 상대속도 30km/h면 감지 구간을 지나는 동안 프레임이 3장뿐이다. **정체 구간·주차장·저속
> 시내 주행용이고, 고속 추돌 경보로는 쓸 수 없다.**

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

**독립된 유닛 두 대**를 앞뒤 범퍼에 각각 물린다
([ADR 0004](docs/decisions/0004-two-device-split.md)).

| 유닛 | 광고 이름 | 센서 |
| --- | --- | --- |
| 후방 | `PARK-01` | 4 |
| 전방 | `PARK-02` | 4 |

| 구분 | 부품 | 수량 |
| --- | --- | --- |
| MCU | ESP32 DevKitC 30핀 | 2 |
| 거리 센서 | JSN-SR04T 방수 초음파 모듈 | 8 |
| 경보 | 액티브 부저 HW-508 | 2 |
| 전원 | 미정 (차량 12V 벅 컨버터 / 18650 + TP4056) | 2 |
| 기구 | 집게형 마운트 | 2 |

### 핀맵 (유닛 공통)

| 채널 | Trig | Echo |
| --- | --- | --- |
| CH1 (왼쪽 끝) | GPIO 13 | GPIO 34 |
| CH2 | GPIO 25 | GPIO 35 |
| CH3 | GPIO 26 | GPIO 36 (VP) |
| CH4 (오른쪽 끝) | GPIO 27 | GPIO 39 (VN) |
| 부저 | GPIO 4 | — |

배선 주의사항(Echo 5V 레벨, 입력 전용 핀, 부저 구동 전류, 25cm 사각지대)은
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
전방 유닛을 구울 때는 `.ino` 상단의 `ZONE_IS_REAR`를 `0`으로 바꾼다.

### 안드로이드 앱

준비물: Android Studio (JDK 17), minSdk 26 / targetSdk 34.

```bash
cd android/ParkAssist
./gradlew assembleDebug        # 디버그 APK 빌드
./gradlew installDebug         # 연결된 기기에 설치
./gradlew test                 # 단위 테스트
```

**실기기 없이 확인하려면** 앱 설정 화면에서 **목업 모드**를 켠다. 전방·후방 양쪽에
가짜 데이터가 흐르고, 주차 접근 / 사각지대 진입 / 센서 고장 / 주행 중 급접근 상황이
주기적으로 재현된다. 설정 화면의 "연결 끊김 시뮬레이션" 버튼으로 재연결 UI도 볼 수 있다.

## BLE 규격

Nordic UART 계열 UUID를 쓰는 고정 길이 바이너리 패킷. 두 유닛이 같은 UUID를 쓰고
**광고 이름으로만 구분**된다. 전체 규격은 [`docs/ble-protocol.md`](docs/ble-protocol.md).

| 방향 | 패킷 | 용도 |
| --- | --- | --- |
| 기기 → 폰 | `[0xAA][d1][d2][d3][d4][XOR]` | 4채널 거리 (cm), 10Hz |
| 폰 → 기기 | `[0xBB][near][mid][far][XOR]` | 경보 거리 기준 변경 |
| 폰 → 기기 | `[0xCC][0x00\|0x01][XOR]` | 전원 대기 / 작동 |

`0xFF`는 255cm가 아니라 **측정 실패**다.

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
| BLE 규격 | 확정 (명령 ACK·설정값 조회는 미정) |
| 하드웨어 | 부품 확정, 전원 방식 미정 |
| 펌웨어 | 구현 완료 — 측정·경보·BLE·NVS. 배터리 서비스는 전원 방식 결정 후 |
| 안드로이드 앱 | 구현 완료 |

펌웨어와 앱 모두 **실기기 검증 전**이다. 단위 테스트(앱 `protocol` 계층 47개)만 통과했다.
