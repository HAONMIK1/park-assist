package com.parkassist.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdsTest {

    private val thresholds = Thresholds.Default // 30 / 60 / 120

    @Test
    fun `경계값이 더 위험한 쪽에 속한다`() {
        assertEquals(ProximityLevel.DANGER, thresholds.levelFor(30))
        assertEquals(ProximityLevel.WARN, thresholds.levelFor(31))
        assertEquals(ProximityLevel.WARN, thresholds.levelFor(60))
        assertEquals(ProximityLevel.CAUTION, thresholds.levelFor(61))
        assertEquals(ProximityLevel.CAUTION, thresholds.levelFor(120))
        assertEquals(ProximityLevel.NONE, thresholds.levelFor(121))
    }

    @Test
    fun `0cm는 위험이다`() {
        assertEquals(ProximityLevel.DANGER, thresholds.levelFor(0))
    }

    @Test
    fun `순서가 뒤집히면 유효하지 않다`() {
        assertTrue(Thresholds(30, 60, 120).isValid)
        assertFalse(Thresholds(60, 30, 120).isValid)
        assertFalse(Thresholds(30, 60, 60).isValid)
        assertFalse(Thresholds(0, 60, 120).isValid)
        assertFalse(Thresholds(30, 60, 255).isValid)
    }

    @Test
    fun `보정하면 항상 유효해진다`() {
        val cases = listOf(
            Thresholds(60, 30, 120),
            Thresholds(30, 30, 30),
            Thresholds(0, 0, 0),
            Thresholds(254, 254, 254),
            Thresholds(-5, 500, 1),
        )

        cases.forEach { input ->
            val fixed = input.coerceOrdering()
            assertTrue("$input → $fixed 가 유효하지 않다", fixed.isValid)
        }
    }

    @Test
    fun `보정은 유효한 값을 건드리지 않는다`() {
        assertEquals(Thresholds.Default, Thresholds.Default.coerceOrdering())
    }

    @Test
    fun `심각도 순서가 선언 순서와 같다`() {
        val ordered = listOf(
            ProximityLevel.NONE,
            ProximityLevel.CAUTION,
            ProximityLevel.WARN,
            ProximityLevel.DANGER,
            ProximityLevel.CRITICAL,
        )
        assertEquals(ordered, ordered.sorted())
        assertEquals(ProximityLevel.CRITICAL, ordered.max())
    }
}
