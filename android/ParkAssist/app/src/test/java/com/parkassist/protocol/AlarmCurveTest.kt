package com.parkassist.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "가까울수록 세게" 라는 요구사항을 숫자로 고정한다. */
class AlarmCurveTest {

    private val thresholds = Thresholds.Default // 30 / 60 / 120

    @Test
    fun `기준 밖에서는 울리지 않는다`() {
        assertEquals(0f, AlarmCurve.intensity(120, thresholds), 0.001f)
        assertEquals(0f, AlarmCurve.intensity(250, thresholds), 0.001f)
        assertNull(AlarmCurve.toneFor(150, blindZone = false, thresholds = thresholds))
    }

    @Test
    fun `기준선이 정해진 강도에 대응한다`() {
        assertEquals(AlarmCurve.INTENSITY_AT_MID, AlarmCurve.intensity(60, thresholds), 0.001f)
        assertEquals(AlarmCurve.INTENSITY_AT_NEAR, AlarmCurve.intensity(30, thresholds), 0.001f)
        assertEquals(1f, AlarmCurve.intensity(0, thresholds), 0.001f)
    }

    @Test
    fun `가까워질수록 강도가 단조 증가한다`() {
        var previous = -1f
        for (cm in 130 downTo 0) {
            val intensity = AlarmCurve.intensity(cm, thresholds)
            assertTrue("$cm cm 에서 강도가 줄었다", intensity >= previous)
            previous = intensity
        }
    }

    @Test
    fun `가까워질수록 간격이 짧아지고 커지고 높아진다`() {
        val far = AlarmCurve.toneFor(110, blindZone = false, thresholds = thresholds)!!
        val mid = AlarmCurve.toneFor(50, blindZone = false, thresholds = thresholds)!!
        val near = AlarmCurve.toneFor(15, blindZone = false, thresholds = thresholds)!!

        assertTrue(far.intervalMs > mid.intervalMs)
        assertTrue(mid.intervalMs > near.intervalMs)

        assertTrue(far.volume < mid.volume)
        assertTrue(mid.volume < near.volume)

        assertTrue(far.pitchRatio < mid.pitchRatio)
        assertTrue(mid.pitchRatio < near.pitchRatio)
    }

    @Test
    fun `아주 가까우면 연속음이 된다`() {
        val near = AlarmCurve.toneFor(2, blindZone = false, thresholds = thresholds)!!

        assertTrue(near.continuous)
    }

    @Test
    fun `사각지대는 측정값이 없어도 최고 강도로 울린다`() {
        val tone = AlarmCurve.toneFor(null, blindZone = true, thresholds = thresholds)

        assertNotNull(tone)
        assertTrue(tone!!.continuous)
        assertEquals(1f, tone.volume, 0.001f)
    }

    @Test
    fun `측정값이 없고 사각지대도 아니면 울리지 않는다`() {
        assertNull(AlarmCurve.toneFor(null, blindZone = false, thresholds = thresholds))
    }

    @Test
    fun `기준을 좁게 잡아도 위험 기준에서의 강도는 그대로다`() {
        // 주행 모드 기준(20/35/50)에서도 "위험 기준에 닿으면 거의 최대"가 유지돼야 한다.
        val driving = DriveMode.DRIVING.defaultThresholds

        assertEquals(AlarmCurve.INTENSITY_AT_NEAR, AlarmCurve.intensity(20, driving), 0.001f)
        assertEquals(0f, AlarmCurve.intensity(50, driving), 0.001f)
    }

    @Test
    fun `급접근 경보는 처음부터 최대 강도다`() {
        assertEquals(1f, AlarmCurve.Urgent.volume, 0.001f)
        assertTrue(AlarmCurve.Urgent.intervalMs < 200L)
    }
}
