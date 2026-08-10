# ParkAssist (Android)

안드로이드 앱. **아직 스캐폴딩만 있다** — 클래스 설계 확정 후 코드를 작성한다.

## 계획된 스택

| 항목 | 값 |
| --- | --- |
| 언어 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| minSdk / targetSdk | 26 / 34 |
| 아키텍처 | MVVM + StateFlow |
| BLE | Android 네이티브 API (외부 라이브러리 없음) |
| 테마 | 다크 기본, 한국어 UI |

## 요구사항 요약

- 메인 화면: 차량 top-down 아이콘 + 후방 4개 호(arc), 거리별 색 변화,
  최단 거리 대형 표시, 전원 ON/OFF 버튼
- 설정 화면: 3단계 거리 기준 슬라이더, 저장 시 `0xBB` 전송, 목업 모드 토글
- Foreground Service로 화면이 꺼져도 BLE 연결 유지
- 연결 끊기면 지수 백오프 자동 재연결
- **목업 모드** — 실기기 없이 가짜 거리 데이터로 UI 테스트.
  `BleRepository` 인터페이스에 Real / Mock 두 구현
- 대상 사용자 60대 — 글자·버튼 크게

## 구현 시 주의

- CCCD(`00002902-...`)에 `ENABLE_NOTIFICATION_VALUE`를 write해야 notify가 온다.
  `setCharacteristicNotification()`만으로는 안 온다.
- Android 12+ `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, 11 이하 `ACCESS_FINE_LOCATION` 분기.
- `onCharacteristicChanged` 오버로드 2개 모두 구현 (API 33에서 시그니처 변경).
- 체크섬 실패 패킷은 버린다. 부분 갱신 금지.
- `0xFF`는 255cm가 아니라 **측정 실패**다. 최단 거리 계산에 넣지 않는다.

패킷 규격은 [`docs/ble-protocol.md`](../../docs/ble-protocol.md).
