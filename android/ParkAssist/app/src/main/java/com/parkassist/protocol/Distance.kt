package com.parkassist.protocol

/**
 * 센서 거리값 한 개.
 *
 * 프로토콜상 `0xFF`는 255cm가 아니라 **측정 실패 / 미연결**이다.
 * 이걸 그냥 Int로 다루면 사각지대에 들어간 순간(가장 위험한 순간) 255cm = 안전으로
 * 계산되는 사고가 난다. 그래서 유효값은 null 가능한 [cm]으로만 꺼낼 수 있게 막았다.
 *
 * @see <a href="../../../../../../../docs/ble-protocol.md">docs/ble-protocol.md</a>
 */
@JvmInline
value class Distance private constructor(val raw: Int) {

    val isValid: Boolean get() = raw != RAW_INVALID

    /** 유효한 거리(cm). 측정 실패면 null. */
    val cm: Int? get() = if (isValid) raw else null

    override fun toString(): String = if (isValid) "${raw}cm" else "invalid"

    companion object {
        /** 측정 실패 / 미연결 센서 */
        const val RAW_INVALID = 0xFF

        /** 0xFF가 예약되어 있으므로 표현 가능한 최대 거리는 254cm다. */
        const val MAX_CM = 254

        val Invalid = Distance(RAW_INVALID)

        /** 패킷 바이트 → Distance. */
        fun fromRaw(raw: Int): Distance {
            val v = raw and 0xFF
            return if (v == RAW_INVALID) Invalid else Distance(v)
        }

        fun fromByte(b: Byte): Distance = fromRaw(b.toInt())

        /** 유효 거리로 생성. 범위를 벗어나면 클램프한다. */
        fun ofCm(cm: Int): Distance = Distance(cm.coerceIn(0, MAX_CM))
    }
}
