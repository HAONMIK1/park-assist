# CLAUDE.md

이 저장소에서 작업할 때 참고하는 지침이다.

## 프로젝트 개요

ESP32 기반 **자동차 집게형 주차/주행 보조 센서**. 앞뒤 번호판에 집게로 물려 장착하는
초음파 거리 경보 장치이다.

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

## 기기 구성 — **두 대다**

| 유닛 | 광고 이름 | 센서 |
| --- | --- | --- |
| 후방 | `PARK-01` | 4 |
| 전방 | `PARK-02` | 4 |

펌웨어는 한 벌이고 `ZONE_IS_REAR` 매크로만 바꿔 굽는다. **두 유닛은 서비스·캐릭터리스틱
UUID가 같고 광고 이름만 다르다.** 앱은 두 기기에 동시에 연결하고 각각 독립적으로
재연결한다 ([ADR 0004](docs/decisions/0004-two-device-split.md)).

> ⚠️ 스캔 필터를 서비스 UUID로만 걸면 두 연결이 같은 기기를 잡는다.
> 연결 전에 **반드시 광고 이름을 확인**할 것 (`DeviceConnection.matchesZone`).

## BLE 규격

**규격의 단일 진실 공급원은 [`docs/ble-protocol.md`](docs/ble-protocol.md)다.**
UUID·패킷 레이아웃·체크섬·경보 곡선을 확인하거나 수정할 일이 있으면 코드에서 유추하지
말고 반드시 그 문서를 먼저 읽는다. 규격을 변경하면 문서 → 펌웨어 → 앱 순서로 함께 고친다.

요약 (자세한 내용은 위 문서 참조):

| 항목 | 값 |
| --- | --- |
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` |
| TX (notify, 기기→폰) | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` |
| RX (write, 폰→기기) | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` |
| 배터리 (선택) | 표준 Battery Service `0x180F` / `0x2A19` |
| 수신 | `[0xAA][d1][d2][d3][d4][XOR]` 6바이트, 10Hz |
| 송신 | `[0xBB][near][mid][far][XOR]` 거리 기준 변경 |
| 송신 | `[0xCC][0x00\|0x01][XOR]` 전원 대기/작동 |

XOR = 마지막 바이트를 제외한 전체 바이트의 XOR.
**`0xFF`는 255cm가 아니라 측정 실패다.** 최소값 계산에 넣으면 안 된다.

## 하드웨어

| 구분 | 부품 | 수량 |
| --- | --- | --- |
| MCU | ESP32 DevKitC 30핀 | 2 |
| 거리 센서 | JSN-SR04T 방수 초음파 | 8 (유닛당 4) |
| 경보 | 액티브 부저 HW-508 | 2 |

### 핀맵 (유닛 공통)

| 채널 | Trig | Echo |
| --- | --- | --- |
| CH1 | GPIO 13 | GPIO 34 |
| CH2 | GPIO 25 | GPIO 35 |
| CH3 | GPIO 26 | GPIO 36 (VP) |
| CH4 | GPIO 27 | GPIO 39 (VN) |
| 부저 | GPIO 4 | — |

- Echo에 배정된 **GPIO 34/35/36/39는 입력 전용**이다. 출력으로 쓰거나 내부 풀업을
  기대하는 코드를 넣지 말 것.
- 채널 ↔ 패킷 매핑: `d1=CH1 … d4=CH4`. 물리 배치는 차량을 위에서 봤을 때 좌 → 우.
- **JSN-SR04T는 약 25cm 이내를 못 읽는다.** 위험 기준 30cm보다 안쪽이라, 가장 위험한
  순간에 `0xFF`가 올라온다. 사각지대 처리를 지우지 말 것.

자세한 배선·전원·주의사항은 [`docs/hardware.md`](docs/hardware.md) 참조.

## 개발 원칙

1. **부저는 ESP32 직결. 폰/BLE 없이도 반드시 작동해야 한다 (안전 최우선).**
   경보 판정과 구동은 펌웨어 안에서 완결한다. 부저를 울리는 경로에 BLE 연결 상태,
   앱 실행 여부, 폰의 존재 여부가 조건으로 끼어들면 안 된다.
2. **센서는 순차 발사.** 동시에 쏘면 상호간섭으로 값이 오염된다. 한 채널의 에코를
   받거나 타임아웃될 때까지 다음 채널을 발사하지 않는다.
3. **`loop()`에서 `delay()` 금지.** `millis()` / `micros()` 기반 논블로킹으로 작성한다.
   에코 대기도 블로킹 `pulseIn()` 대신 인터럽트 + 타임아웃으로 처리한다.
4. **앱은 보조 표시 장치다. 주 경고 채널이 아니다.**
   앱도 소리를 내지만([ADR 0003](docs/decisions/0003-app-alarm-and-drive-mode.md)) 그건
   보조다. 앱이 죽어도 기기 부저는 그대로 울려야 한다. 설정의 "앱 경고음" 토글이
   기기 부저에 영향을 주면 안 된다.

## 경보 강도 곡선

거리 기준 3개는 **경계선**일 뿐이고 그 사이도 연속적으로 세진다.
`far`→0, `mid`→0.34, `near`→0.67, 0cm→1.0. 강도가 0.93을 넘으면 연속음.

**펌웨어(`alarmIntensity()` / `beepIntervalMs()`)와 앱(`protocol/AlarmCurve.kt`)에
중복 구현되어 있다.** 한쪽을 바꾸면 다른 쪽도 같은 PR에서 고친다. 기준은
[`docs/ble-protocol.md`](docs/ble-protocol.md) 6절이고, 앱 쪽은 `AlarmCurveTest`가 고정한다.

## 주차 / 주행 모드

| | 주차 | 주행 |
| --- | --- | --- |
| 경보 기준 | 거리 | **접근 속도** |
| 앱 소리 | 연속 곡선 | 급접근일 때만 최대 강도 |
| 기기 기준값 기본 | 30 / 60 / 120 | 20 / 35 / 50 |

주행 중에는 앞뒤 차가 1~2m에 있는 게 정상이라 거리로 경보를 내면 정체 구간에서 쉬지 않고
울린다. 그래서 `ApproachDetector`가 최근 0.6초의 접근 속도를 보고 판정한다.

**주행 감지에는 물리적 한계가 있다.** 센서 측정 한계 250cm / 10Hz라 30km/h 상대속도면
프레임 3장뿐이다. 정체·주차장·저속 시내용이고 고속 추돌 경보로는 쓸 수 없다.
이 한계를 지우거나 과장하는 문구를 넣지 말 것.

## 빌드

### 펌웨어

Arduino IDE 또는 arduino-cli. 보드: **ESP32 Dev Module**.
스케치 폴더명과 `.ino` 파일명은 반드시 같아야 하므로 `park-assist-fw` 이름을 바꾸지 말 것.

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/park-assist-fw
arduino-cli upload  --fqbn esp32:esp32:esp32 -p /dev/ttyUSB0 firmware/park-assist-fw
```

전방 유닛을 구울 때는 `.ino` 상단의 `ZONE_IS_REAR`를 `0`으로 바꾼다.

### 안드로이드

```bash
cd android/ParkAssist
./gradlew assembleDebug
./gradlew test          # protocol 계층 단위 테스트 (47개)
```

## 안드로이드 구조

```
com.parkassist/
├── protocol/   # ★ Android 의존성 0 — 순수 단위 테스트 대상
│               #   Distance, Thresholds, ProtocolCodec, ProximityResolver,
│               #   AlarmCurve, ApproachDetector, SensorZone, DriveMode
├── ble/        # BleRepository(인터페이스) + DeviceConnection(기기 1대)
│               #   + MultiZoneBleRepository(2대 묶음) + MockBleRepository
├── data/       # SettingsStore (DataStore)
├── domain/     # ProximityMonitor — 화면과 소리가 함께 쓰는 상태 한 벌
├── audio/      # AlarmPlayer (AudioTrack 사인파 합성)
├── service/    # ParkAssistService (Foreground, 연결과 소리의 소유자)
├── di/         # AppContainer (수동 DI)
└── ui/         # main/ settings/ theme/
```

**판정 로직은 전부 `protocol/`에 둔다.** Android API를 쓰지 않으므로 로봇 없이 JVM
테스트로 검증된다. 시간이 필요한 로직은 `now`를 인자로 받게 만든다.

## 안드로이드 작업 시 주의

- **CCCD 필수.** `setCharacteristicNotification()`만 호출하면 notify가 오지 않는다.
  CCCD 디스크립터 `00002902-0000-1000-8000-00805f9b34fb`에
  `ENABLE_NOTIFICATION_VALUE`를 write해야 한다. **CCCD write 성공 뒤에야 "연결됨"이다.**
- **GATT 작업은 한 번에 하나.** 겹쳐 부르면 조용히 실패한다. `DeviceConnection`의
  작업 큐를 우회하지 말 것.
- **권한 분기.** Android 12(API 31)+ 는 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`,
  11 이하는 `ACCESS_FINE_LOCATION`. 매니페스트에 `neverForLocation`을 선언했으므로
  스캔 결과로 위치를 추론하는 코드를 넣으면 안 된다.
- **`onCharacteristicChanged` / `onCharacteristicRead` 오버로드 2개씩 모두 구현.**
  API 33에서 시그니처가 바뀌었다. 구버전(`characteristic.value`)과 신버전(`ByteArray`
  파라미터) 양쪽이 필요하고, 구버전 쪽은 `SDK_INT < 33` 가드를 둬서 중복 처리를 막는다.
- **체크섬 검증 후 사용.** XOR이 안 맞는 패킷은 조용히 버린다. 부분 갱신 금지.
- **목업 모드를 깨뜨리지 말 것.** `BleRepository`는 인터페이스이고 Real/Mock 두 구현이
  있다. 실기기 없이 UI를 테스트하는 유일한 수단이므로 `BluetoothDevice` 같은 Real 전용
  타입을 UI 레이어로 누출시키지 않는다.
- **대상 사용자는 60대다.** 글자·버튼을 크게, 정보 밀도는 낮게 유지한다. 경고음
  기준 주파수도 고음역 청력 저하를 고려해 1.8kHz로 낮춰 잡았다.

## 설계 결정

되돌리기 어렵거나 나중에 "왜 이렇게 했지?"가 될 결정은 `docs/decisions/`에 ADR로
기록한다. 작성법은 [`docs/decisions/README.md`](docs/decisions/README.md) 참조.
