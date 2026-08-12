package com.parkassist.ble

import kotlin.random.Random

/**
 * 재연결 지수 백오프.
 *
 * 1s → 2s → 4s → 8s → 16s → 30s(상한). 지터를 섞어서 기기가 재부팅될 때 폰 여러 대가
 * 같은 순간에 몰리는 걸 피한다.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 30_000L,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /** @param attempt 1부터 시작하는 시도 횟수 */
    fun delayFor(attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)

        // shl 오버플로 방지 — 상한에 닿는 지점 이후는 계산할 필요가 없다.
        val backoff = if (n >= MAX_SHIFT) {
            maxDelayMs
        } else {
            (baseDelayMs shl (n - 1)).coerceAtMost(maxDelayMs)
        }

        val jitter = backoff * jitterRatio
        if (jitter <= 0.0) return backoff

        val withJitter = backoff + random.nextDouble(-jitter, jitter)
        return withJitter.toLong().coerceIn(baseDelayMs / 2, maxDelayMs * 2)
    }

    private companion object {
        const val MAX_SHIFT = 24
    }
}
