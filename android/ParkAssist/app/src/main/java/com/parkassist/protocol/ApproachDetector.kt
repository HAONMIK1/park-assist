package com.parkassist.protocol

/**
 * 주행 중 **급접근** 감지.
 *
 * 주행 중에는 뒤차가 1~2m에 있는 게 정상이라 거리만으로는 경보를 낼 수 없다.
 * 대신 최근 [windowMs] 동안 거리가 줄어든 **속도**를 보고, 빠르게 좁혀 올 때만 울린다.
 *
 * 한 번 조건을 만족하면 [holdMs] 동안 경보를 유지한다. 값이 경계선에서 떨릴 때
 * 소리가 끊겼다 붙었다 하는 걸 막기 위해서다.
 *
 * ## 감지 한계 (중요)
 *
 * JSN-SR04T의 측정 한계가 약 250cm이고 갱신이 10Hz다. 상대속도가 빠를수록 감지 구간을
 * 지나가는 프레임 수가 줄어든다.
 *
 * | 상대속도 | 250cm를 지나는 시간 | 프레임 수 |
 * | --- | --- | --- |
 * | 10 km/h (2.8 m/s) | 0.90초 | 약 9 |
 * | 30 km/h (8.3 m/s) | 0.30초 | 약 3 |
 * | 60 km/h (16.7 m/s) | 0.15초 | 약 1~2 |
 *
 * 즉 **정체 구간·주차장·저속 시내 주행에서 쓸모가 있고, 고속 추돌 경보로는 쓸 수 없다.**
 * 고속에서 쓰려면 레이더 등 감지 거리가 훨씬 긴 센서가 필요하다.
 *
 * 시간은 전부 인자로 받으므로 테스트에서 결정적으로 검증된다.
 *
 * @param alertWithinCm 이 거리 안에서만 급접근을 따진다
 * @param closingSpeedCmPerSec 이 속도 이상으로 좁혀 오면 경보
 * @param immediateCm 속도와 무관하게 무조건 경보로 보는 거리
 * @param holdMs 한 번 걸리면 유지할 시간
 * @param windowMs 속도를 계산할 구간
 */
class ApproachDetector(
    private val alertWithinCm: Int = 150,
    private val closingSpeedCmPerSec: Int = 60,
    private val immediateCm: Int = 40,
    private val holdMs: Long = 2_500L,
    private val windowMs: Long = 600L,
) {
    private data class Sample(val atMs: Long, val cm: Int)

    private val samples = ArrayDeque<Sample>()
    private var alertUntilMs = 0L

    fun reset() {
        samples.clear()
        alertUntilMs = 0L
    }

    /**
     * @param nearestCm 유효한 최단 거리. 측정값이 없으면 null.
     * @param blindZone 사각지대 유지 중 — 25cm 안에 뭔가 있다는 뜻이므로 즉시 경보.
     * @return 지금 급접근 경보를 울려야 하는지.
     */
    fun update(nearestCm: Int?, blindZone: Boolean, nowMs: Long): Boolean {
        if (blindZone) {
            alertUntilMs = nowMs + holdMs
            return true
        }

        if (nearestCm == null) {
            // 측정이 없으면 새 판단을 하지 않는다. 남은 유지 시간만 흘려보낸다.
            samples.clear()
            return nowMs < alertUntilMs
        }

        samples.addLast(Sample(nowMs, nearestCm))
        while (samples.size > 2 && nowMs - samples.first().atMs > windowMs) {
            samples.removeFirst()
        }

        if (nearestCm <= immediateCm || isClosingFast(nearestCm, nowMs)) {
            alertUntilMs = nowMs + holdMs
        }
        return nowMs < alertUntilMs
    }

    /** 최근 구간의 평균 접근 속도(cm/s)가 기준을 넘는지. */
    private fun isClosingFast(nearestCm: Int, nowMs: Long): Boolean {
        if (nearestCm > alertWithinCm) return false

        val oldest = samples.firstOrNull() ?: return false
        val elapsedMs = nowMs - oldest.atMs
        if (elapsedMs < MIN_WINDOW_MS) return false // 표본이 너무 짧으면 속도가 튄다

        val closedCm = oldest.cm - nearestCm
        if (closedCm <= 0) return false // 멀어지는 중

        val speedCmPerSec = closedCm * 1000L / elapsedMs
        return speedCmPerSec >= closingSpeedCmPerSec
    }

    private companion object {
        const val MIN_WINDOW_MS = 200L
    }
}
