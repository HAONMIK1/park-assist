package com.parkassist.ble

import java.util.UUID

/**
 * docs/ble-protocol.md 의 GATT 프로파일. 여기 값이 문서와 다르면 그게 버그다.
 *
 * 기기 두 대(PARK-01 후방 / PARK-02 전방)가 **같은 서비스·캐릭터리스틱 UUID**를 쓴다.
 * 구분은 광고 이름으로만 한다.
 */
object BleConstants {

    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** notify — 기기 → 폰. 앱은 이걸 **구독**한다. */
    val TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** write — 폰 → 기기. 앱은 여기에 **쓴다**. */
    val RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /**
     * Client Characteristic Configuration Descriptor.
     *
     * `setCharacteristicNotification()`만 부르면 알림이 오지 않는다.
     * 여기에 `ENABLE_NOTIFICATION_VALUE`를 write해야 한다.
     */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** 표준 Battery Service — 기기가 제공하면 잔량을 읽는다(없으면 무시). */
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    /** 이 시간 동안 측정 패킷이 없으면 "신호 없음"으로 본다(연결 끊김과는 다르다). */
    const val TELEMETRY_STALE_MS = 1_000L

    /** 스캔이 이만큼 지나도 못 찾으면 중단하고 백오프 후 다시 시도한다. */
    const val SCAN_TIMEOUT_MS = 20_000L

    const val GATT_OP_TIMEOUT_MS = 5_000L
}
