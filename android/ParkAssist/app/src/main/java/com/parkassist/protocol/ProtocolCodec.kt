package com.parkassist.protocol

/**
 * BLE 패킷 인코딩 / 디코딩. **Android 의존성이 없다** — 순수 단위 테스트 대상이다.
 *
 * 체크섬 검증은 전부 여기서 한다. 어긋난 패킷은 null을 돌려주고, 호출부는 조용히
 * 버린다. 일부 바이트만 반영하는 부분 갱신은 절대 하지 않는다.
 *
 * 규격: docs/ble-protocol.md
 */
object ProtocolCodec {

    const val TYPE_TELEMETRY: Byte = 0xAA.toByte()
    const val TYPE_THRESHOLDS: Byte = 0xBB.toByte()
    const val TYPE_POWER: Byte = 0xCC.toByte()

    const val POWER_STANDBY: Byte = 0x00
    const val POWER_ACTIVE: Byte = 0x01

    const val TELEMETRY_SIZE = 6
    const val THRESHOLDS_SIZE = 5
    const val POWER_SIZE = 3

    /** 마지막 바이트를 제외한 전체 XOR. [length]는 체크섬을 뺀 길이다. */
    fun checksum(bytes: ByteArray, length: Int = bytes.size): Byte {
        var acc = 0
        for (i in 0 until length) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc.toByte()
    }

    /**
     * `[0xAA][d1][d2][d3][d4][XOR]` 파싱.
     *
     * @return 길이·타입·체크섬이 하나라도 어긋나면 null.
     */
    fun parseTelemetry(bytes: ByteArray): SensorFrame? {
        if (bytes.size != TELEMETRY_SIZE) return null
        if (bytes[0] != TYPE_TELEMETRY) return null
        if (checksum(bytes, TELEMETRY_SIZE - 1) != bytes[TELEMETRY_SIZE - 1]) return null

        return SensorFrame(
            List(SensorFrame.CHANNEL_COUNT) { ch -> Distance.fromByte(bytes[1 + ch]) }
        )
    }

    /**
     * `[0xBB][near][mid][far][XOR]` 조립.
     *
     * @return 불변식(near < mid < far)을 어기면 null — 잘못된 기준을 기기에 밀어넣지 않는다.
     */
    fun encodeThresholds(thresholds: Thresholds): ByteArray? {
        if (!thresholds.isValid) return null

        val out = ByteArray(THRESHOLDS_SIZE)
        out[0] = TYPE_THRESHOLDS
        out[1] = thresholds.nearCm.toByte()
        out[2] = thresholds.midCm.toByte()
        out[3] = thresholds.farCm.toByte()
        out[THRESHOLDS_SIZE - 1] = checksum(out, THRESHOLDS_SIZE - 1)
        return out
    }

    /** `[0xCC][0x00|0x01][XOR]` 조립. */
    fun encodePower(active: Boolean): ByteArray {
        val out = ByteArray(POWER_SIZE)
        out[0] = TYPE_POWER
        out[1] = if (active) POWER_ACTIVE else POWER_STANDBY
        out[POWER_SIZE - 1] = checksum(out, POWER_SIZE - 1)
        return out
    }
}
