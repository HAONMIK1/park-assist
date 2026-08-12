package com.parkassist.protocol

/**
 * 경보 거리 기준. `0xBB` 패킷으로 기기에 전달된다.
 *
 * 필드 이름은 프로토콜 문서의 near/mid/far를 그대로 따른다. UI 표기와의 대응은 이렇다.
 *
 * | 필드 | UI 표기 | 색 | 기본값 |
 * | --- | --- | --- | --- |
 * | [nearCm] | 위험 | 빨강 | 30cm |
 * | [midCm]  | 경고 | 주황 | 60cm |
 * | [farCm]  | 주의 | 초록 | 120cm |
 *
 * 불변식(near < mid < far)을 생성자에서 강제하지 않는 이유: 슬라이더를 끄는 도중
 * 순간적으로 순서가 뒤집힐 수 있어서다. 대신 [coerceOrdering]으로 보정하고,
 * [isValid]가 아닌 값은 [ProtocolCodec.encodeThresholds]가 전송을 거부한다.
 */
data class Thresholds(
    val nearCm: Int,
    val midCm: Int,
    val farCm: Int,
) {

    val isValid: Boolean
        get() = nearCm in MIN..MAX && midCm in MIN..MAX && farCm in MIN..MAX &&
            nearCm < midCm && midCm < farCm

    /** 순서가 뒤집히지 않도록 보정한다. 슬라이더 조작 중에 쓴다. */
    fun coerceOrdering(): Thresholds {
        val near = nearCm.coerceIn(MIN, MAX - 2)
        val mid = midCm.coerceIn(near + 1, MAX - 1)
        val far = farCm.coerceIn(mid + 1, MAX)
        return Thresholds(near, mid, far)
    }

    /** 거리 → 경보 단계. 사각지대([ProximityLevel.CRITICAL])는 여기서 판단하지 않는다. */
    fun levelFor(cm: Int): ProximityLevel = when {
        cm <= nearCm -> ProximityLevel.DANGER
        cm <= midCm -> ProximityLevel.WARN
        cm <= farCm -> ProximityLevel.CAUTION
        else -> ProximityLevel.NONE
    }

    companion object {
        const val MIN = 1
        const val MAX = Distance.MAX_CM

        val Default = Thresholds(nearCm = 30, midCm = 60, farCm = 120)

        // 슬라이더 범위 — 기본값이 가운데쯤 오도록 잡았다.
        val NEAR_RANGE = 10..80
        val MID_RANGE = 20..150
        val FAR_RANGE = 40..MAX
    }
}
