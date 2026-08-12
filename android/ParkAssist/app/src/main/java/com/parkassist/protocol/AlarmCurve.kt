package com.parkassist.protocol

import kotlin.math.pow

/**
 * 한 번의 경고음을 어떻게 낼지.
 *
 * @param intervalMs 소리가 다시 시작되기까지의 간격
 * @param beepMs 한 번 울리는 길이
 * @param volume 0..1
 * @param pitchRatio 기준음 대비 배율 (1.0 = 기준)
 * @param continuous 끊지 않고 계속 울릴지
 */
data class AlarmTone(
    val intervalMs: Long,
    val beepMs: Long,
    val volume: Float,
    val pitchRatio: Float,
    val continuous: Boolean,
)

/**
 * 거리 → 경고음 강도.
 *
 * 단계가 3개뿐이면 기준선을 넘는 순간에만 소리가 바뀌어서, 그 사이에서 얼마나
 * 가까워지는지 알 수 없다. 그래서 **거리에 따라 연속적으로** 세지게 한다.
 *
 * - 간격: 멀면 느리게 → 가까우면 촘촘하게 (등비 보간이라 귀에 고르게 들린다)
 * - 음량: 멀면 작게 → 가까우면 크게
 * - 음높이: 가까울수록 높게
 * - 최대치 근처에서는 끊지 않고 연속음
 *
 * 사용자가 설정한 세 기준([Thresholds])이 강도 0.34 / 0.67 / 1.0에 고정으로 대응하므로,
 * 기준을 바꿔도 "위험 기준에 닿으면 거의 연속음"이라는 감각은 그대로 유지된다.
 *
 * ## 펌웨어와의 관계
 *
 * 주 경보 채널은 기기의 부저다(ADR 0001). 이 곡선은 펌웨어의
 * `alarmIntensity()` / `beepIntervalMs()`와 **같은 모양**이어야 하며, 한쪽을 바꾸면
 * 다른 쪽도 같은 PR에서 고친다. 앱 소리는 어디까지나 보조다(ADR 0003).
 */
object AlarmCurve {

    /** 기준선에 대응하는 강도. 이 값을 바꾸면 펌웨어도 같이 바꿔야 한다. */
    const val INTENSITY_AT_FAR = 0.0f
    const val INTENSITY_AT_MID = 0.34f
    const val INTENSITY_AT_NEAR = 0.67f

    private const val INTERVAL_MIN_MS = 80.0
    private const val INTERVAL_MAX_MS = 1000.0

    private const val VOLUME_MIN = 0.35f
    private const val VOLUME_MAX = 1.0f

    private const val PITCH_MIN = 1.0f
    private const val PITCH_MAX = 1.45f

    /** 이 강도를 넘으면 연속음으로 전환한다. */
    private const val CONTINUOUS_FROM = 0.93f

    /**
     * 주행 중 급접근 경보([ApproachDetector]).
     *
     * 거리에 비례시키지 않고 **처음부터 최대 강도**로 낸다. 주행 중에는 "조금씩
     * 세지는" 경고가 의미 없다 — 반응할 시간이 1초도 안 되기 때문이다.
     * 내비게이션 음성 위로 들려야 하므로 재생 쪽에서 오디오 포커스를 잠시 가져간다.
     */
    val Urgent = AlarmTone(
        intervalMs = 130L,
        beepMs = 85L,
        volume = 1.0f,
        pitchRatio = PITCH_MAX,
        continuous = false,
    )

    /**
     * 거리 → 강도 0..1. 기준 밖이면 0.
     *
     * far → mid → near → 0cm 구간을 각각 선형 보간해서 이어 붙인다.
     */
    fun intensity(cm: Int, thresholds: Thresholds): Float = when {
        cm >= thresholds.farCm -> 0f

        cm >= thresholds.midCm -> segment(
            cm, thresholds.farCm, thresholds.midCm, INTENSITY_AT_FAR, INTENSITY_AT_MID
        )

        cm >= thresholds.nearCm -> segment(
            cm, thresholds.midCm, thresholds.nearCm, INTENSITY_AT_MID, INTENSITY_AT_NEAR
        )

        else -> segment(cm, thresholds.nearCm, 0, INTENSITY_AT_NEAR, 1f)
    }

    /**
     * @param cm 유효한 최단 거리. 측정값이 없으면 null.
     * @param blindZone 사각지대 유지 중이면 무조건 최고 강도.
     * @return 울릴 필요가 없으면 null.
     */
    fun toneFor(cm: Int?, blindZone: Boolean, thresholds: Thresholds): AlarmTone? {
        val u = when {
            blindZone -> 1f
            cm == null -> return null
            else -> intensity(cm, thresholds)
        }
        if (u <= 0f) return null

        // 등비 보간 — 간격이 절반씩 줄어드는 느낌이라 선형보다 자연스럽게 들린다.
        val interval = INTERVAL_MAX_MS * (INTERVAL_MIN_MS / INTERVAL_MAX_MS).pow(u.toDouble())
        val continuous = u >= CONTINUOUS_FROM

        return AlarmTone(
            intervalMs = interval.toLong(),
            beepMs = (interval * 0.45).toLong().coerceAtLeast(45L),
            volume = VOLUME_MIN + (VOLUME_MAX - VOLUME_MIN) * u,
            pitchRatio = PITCH_MIN + (PITCH_MAX - PITCH_MIN) * u,
            continuous = continuous,
        )
    }

    /** [xFar]에서 [yLo], [xNear]에서 [yHi]가 되는 선형 보간. */
    private fun segment(cm: Int, xFar: Int, xNear: Int, yLo: Float, yHi: Float): Float {
        val span = (xFar - xNear).toFloat()
        if (span <= 0f) return yHi
        val t = ((xFar - cm).toFloat() / span).coerceIn(0f, 1f)
        return yLo + (yHi - yLo) * t
    }
}
