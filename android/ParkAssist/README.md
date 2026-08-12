# ParkAssist (Android)

전방(`PARK-02`)·후방(`PARK-01`) 두 기기에 **동시에** BLE 연결해서 거리와 경보를 보여주는 앱.

| 항목 | 값 |
| --- | --- |
| 언어 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| minSdk / targetSdk | 26 / 34 |
| 아키텍처 | MVVM + StateFlow, 수동 DI |
| BLE | Android 네이티브 API (외부 라이브러리 없음) |
| 테마 | 다크 고정, 한국어 |

## 구조

```
com.parkassist/
├── protocol/   # ★ Android 의존성 0 — 순수 JVM 단위 테스트 대상
│   ├── Distance.kt          0xFF를 255cm로 오인할 수 없게 만든 타입
│   ├── Thresholds.kt        near/mid/far + 불변식
│   ├── ProtocolCodec.kt     패킷 조립/파싱 + XOR 검증
│   ├── ProximityResolver.kt 사각지대(25cm 이내) 판정
│   ├── AlarmCurve.kt        거리 → 경보 강도 (펌웨어와 같은 곡선)
│   ├── ApproachDetector.kt  주행 중 급접근 감지
│   ├── SensorZone.kt        FRONT / REAR = 기기 두 대
│   └── DriveMode.kt         주차 / 주행
├── ble/
│   ├── BleRepository.kt         인터페이스 — Real/Mock의 이음매
│   ├── DeviceConnection.kt      기기 1대와의 GATT 연결 (작업 큐 포함)
│   ├── MultiZoneBleRepository.kt 두 대를 묶은 실제 구현
│   ├── MockBleRepository.kt     실기기 없이 UI를 굴리는 구현
│   ├── SwitchableBleRepository.kt 목업 토글 시 인스턴스 교체
│   ├── ReconnectPolicy.kt       지수 백오프
│   └── BlePermissions.kt        SDK 버전 분기
├── data/SettingsStore.kt        DataStore — 모드별 기준값, 목업/소리 토글
├── domain/ProximityMonitor.kt   화면과 소리가 함께 쓰는 상태 한 벌
├── audio/AlarmPlayer.kt         AudioTrack 사인파 합성 (+오디오 포커스)
├── service/ParkAssistService.kt Foreground — 연결과 소리의 소유자
├── di/AppContainer.kt           수동 DI
└── ui/  main/ settings/ theme/
```

**판정 로직은 전부 `protocol/`에 있다.** Android API를 쓰지 않고 시간은 인자로 받으므로
실기기 없이 JVM 테스트로 검증된다 (`./gradlew test`, 47개).

## 화면

**메인** — 최상단에 주차/주행 모드 전환, 그 아래 존별 연결 상태와 배터리(두 기기 중 낮은
쪽). 가운데는 위에서 본 차량과 **앞뒤 부채꼴 4개씩**. 가까워질수록 바깥쪽부터 안쪽으로
차오르며 회색 → 초록 → 주황 → 빨강으로 바뀐다. 아래에 최단 거리를 아주 크게, 그 아래
전원 버튼과 설정.

**설정** — 지금 모드의 3단계 거리 기준(슬라이더 + ±버튼), 앱 경고음 토글, 목업 모드 토글.
기준값은 **모드별로 따로** 저장된다.

## 구현 시 주의

- **두 기기가 같은 서비스 UUID를 광고한다.** 스캔에서 이름을 확인하지 않으면 두 연결이
  같은 기기를 잡는다 (`DeviceConnection.matchesZone`).
- CCCD(`00002902-...`)에 `ENABLE_NOTIFICATION_VALUE`를 write해야 notify가 온다.
  `setCharacteristicNotification()`만으로는 안 온다. **write 성공 뒤에야 "연결됨"이다.**
- GATT 작업은 한 번에 하나만 가능하다. `DeviceConnection`의 작업 큐를 우회하지 말 것.
- Android 12+ `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, 11 이하 `ACCESS_FINE_LOCATION` 분기.
  매니페스트에 `neverForLocation`을 선언했으므로 스캔 결과로 위치를 추론하면 안 된다.
- `onCharacteristicChanged` / `onCharacteristicRead` 오버로드를 각각 2개 다 구현
  (API 33에서 시그니처 변경). 구버전 쪽은 `SDK_INT < 33` 가드로 중복 처리를 막는다.
- 체크섬 실패 패킷은 버린다. 부분 갱신 금지.
- **`0xFF`는 255cm가 아니라 측정 실패다.** `Distance` 타입이 이걸 강제한다.
- 앱 경고음 토글은 **앱 소리만** 끈다. 기기 부저에 영향을 주면 안 된다(ADR 0001).

패킷 규격은 [`docs/ble-protocol.md`](../../docs/ble-protocol.md),
경보 곡선은 그 문서 6절.

## 목업 모드

설정에서 켜면 실기기 없이 전 화면이 동작한다. 한 주기 안에 이런 상황이 다 나온다.

- 후방: 물체 접근 → 근접 유지 → 후퇴 (26초 주기)
- 후방: 25cm 이내 진입 시 `0xFF` → "매우 가까움" + 최고 경보
- 후방 CH2: 주기적 측정 실패 (고장 센서 표시)
- 전방: 9초마다 200cm → 50cm 급접근 (주행 모드 경보 검증)
- 버튼으로 연결 끊김 → 지수 백오프 재연결
