package com.parkassist.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    @Test
    fun `지터 없이 두 배씩 늘어난다`() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000L, maxDelayMs = 30_000L, jitterRatio = 0.0)

        assertEquals(1_000L, policy.delayFor(1))
        assertEquals(2_000L, policy.delayFor(2))
        assertEquals(4_000L, policy.delayFor(3))
        assertEquals(8_000L, policy.delayFor(4))
        assertEquals(16_000L, policy.delayFor(5))
    }

    @Test
    fun `상한을 넘지 않는다`() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000L, maxDelayMs = 30_000L, jitterRatio = 0.0)

        assertEquals(30_000L, policy.delayFor(6))
        assertEquals(30_000L, policy.delayFor(20))
        // 큰 횟수에서 shl 오버플로로 음수가 나오면 안 된다.
        assertEquals(30_000L, policy.delayFor(1_000))
        assertEquals(30_000L, policy.delayFor(Int.MAX_VALUE))
    }

    @Test
    fun `0이나 음수 횟수도 첫 시도로 취급한다`() {
        val policy = ReconnectPolicy(jitterRatio = 0.0)

        assertEquals(1_000L, policy.delayFor(0))
        assertEquals(1_000L, policy.delayFor(-5))
    }

    @Test
    fun `지터는 정해진 비율 안에서만 흔들린다`() {
        val policy = ReconnectPolicy(jitterRatio = 0.2, random = Random(20260810))

        repeat(200) {
            val delay = policy.delayFor(3) // 기준 4000ms
            assertTrue("$delay 가 범위를 벗어났다", delay in 3_200L..4_800L)
        }
    }

    @Test
    fun `항상 양수를 돌려준다`() {
        val policy = ReconnectPolicy(random = Random(1))

        (1..40).forEach { attempt ->
            assertTrue(policy.delayFor(attempt) > 0)
        }
    }
}
