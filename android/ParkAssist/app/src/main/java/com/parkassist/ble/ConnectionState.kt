package com.parkassist.ble

/** 연결 상태. 화면 상단 표시가 이걸 그대로 따라간다. */
sealed interface ConnectionState {

    /** 시작 전 */
    data object Idle : ConnectionState

    /** 블루투스가 꺼져 있음 — 사용자가 켜야 한다 */
    data object BluetoothOff : ConnectionState

    /** 런타임 권한이 없음 */
    data object PermissionRequired : ConnectionState

    /** PARK-01 을 찾는 중 */
    data object Scanning : ConnectionState

    /** 찾았고 GATT 연결/서비스 탐색 중 (CCCD write 완료 전) */
    data object Connecting : ConnectionState

    /** CCCD write까지 끝나 알림을 받을 준비가 된 상태 */
    data object Connected : ConnectionState

    /** 끊겨서 재시도 대기 중 */
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionState

    /** 끊김 (자동 재연결이 꺼져 있거나 중단된 상태) */
    data class Disconnected(val statusCode: Int? = null) : ConnectionState
}
