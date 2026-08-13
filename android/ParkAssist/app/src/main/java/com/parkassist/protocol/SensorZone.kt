package com.parkassist.protocol

/**
 * 센서 묶음 하나 = 기기 한 대.
 *
 * 앞뒤 범퍼는 4m쯤 떨어져 있다. 한 보드에서 8개를 끌면 차 안으로 방수 케이블을
 * 길게 통과시켜야 하므로, 범퍼마다 독립된 기기를 물린다(ADR 0004).
 * 앱이 두 기기에 **동시에** BLE 연결한다.
 *
 * 덕분에 패킷 규격은 4채널 6바이트 그대로다. 한쪽 기기가 죽어도 다른 쪽은 계속 산다.
 */
enum class SensorZone {
    REAR,
    FRONT,
    ;

    /** 광고 이름. 앱은 이 이름으로 두 기기를 구분한다. */
    val deviceName: String
        get() = when (this) {
            REAR -> "PARK-01"
            FRONT -> "PARK-02"
        }
}
