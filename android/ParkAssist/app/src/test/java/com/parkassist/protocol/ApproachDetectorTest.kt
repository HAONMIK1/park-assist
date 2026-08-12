package com.parkassist.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주행 모드의 핵심 판정.
 *
 * "가까이 있다"가 아니라 "빠르게 좁혀 온다"를 잡아야 한다. 이걸 거리 기준으로 바꾸면
 * 정체 구간에서 쉬지 않고 울린다.
 */
class ApproachDetectorTest {

    @Test
    fun `가까이 있어도 좁혀 오지 않으면 울리지 않는다`() {
        val detector = ApproachDetector()

        // 뒤차가 100cm에 1.5초간 그대로 붙어 있는 상황 — 정체 구간에서 정상이다.
        var alerted = false
        for (step in 0..15) {
            alerted = detector.update(100, blindZone = false, nowMs = step * 100L)
        }

        assertFalse(alerted)
    }

    @Test
    fun `빠르게 좁혀 오면 울린다`() {
        val detector = ApproachDetector(closingSpeedCmPerSec = 60)

        // 200cm → 80cm 를 0.6초에 = 200cm/s
        var alerted = false
        var cm = 200
        for (step in 0..6) {
            alerted = detector.update(cm, blindZone = false, nowMs = step * 100L)
            cm -= 20
        }

        assertTrue(alerted)
    }

    @Test
    fun `천천히 다가오는 건 울리지 않는다`() {
        val detector = ApproachDetector(closingSpeedCmPerSec = 60, immediateCm = 40)

        // 초당 20cm — 주차하듯 느린 접근. 주행 경보 대상이 아니다.
        var alerted = false
        var cm = 140
        for (step in 0..20) {
            alerted = detector.update(cm, blindZone = false, nowMs = step * 100L)
            if (cm > 60) cm -= 2
        }

        assertFalse(alerted)
    }

    @Test
    fun `멀리서 빠르게 접근해도 감시 거리 밖이면 울리지 않는다`() {
        val detector = ApproachDetector(alertWithinCm = 150)

        var alerted = false
        var cm = 250
        for (step in 0..3) {
            alerted = detector.update(cm, blindZone = false, nowMs = step * 100L)
            cm -= 20 // 200cm/s 지만 아직 160cm 밖
        }

        assertFalse(alerted)
    }

    @Test
    fun `아주 가까우면 속도와 무관하게 울린다`() {
        val detector = ApproachDetector(immediateCm = 40)

        val alerted = detector.update(35, blindZone = false, nowMs = 0L)

        assertTrue(alerted)
    }

    @Test
    fun `사각지대는 즉시 경보다`() {
        val detector = ApproachDetector()

        assertTrue(detector.update(null, blindZone = true, nowMs = 0L))
    }

    @Test
    fun `한 번 걸리면 유지 시간 동안 이어진다`() {
        val detector = ApproachDetector(immediateCm = 40, holdMs = 2_500L)
        detector.update(35, blindZone = false, nowMs = 0L)

        // 물체가 사라져도(측정 실패) 유지 시간 동안은 경보가 이어진다.
        assertTrue(detector.update(null, blindZone = false, nowMs = 2_000L))
        assertFalse(detector.update(null, blindZone = false, nowMs = 3_000L))
    }

    @Test
    fun `멀어지는 중에는 울리지 않는다`() {
        val detector = ApproachDetector()

        var alerted = false
        var cm = 60
        for (step in 0..6) {
            alerted = detector.update(cm, blindZone = false, nowMs = step * 100L)
            cm += 20
        }

        assertFalse(alerted)
    }

    @Test
    fun `표본이 한 개뿐이면 속도를 주장하지 않는다`() {
        val detector = ApproachDetector(immediateCm = 10)

        assertFalse(detector.update(100, blindZone = false, nowMs = 0L))
    }

    @Test
    fun `reset 하면 이전 이력이 사라진다`() {
        val detector = ApproachDetector(immediateCm = 40, holdMs = 2_500L)
        detector.update(35, blindZone = false, nowMs = 0L)

        detector.reset()

        assertFalse(detector.update(null, blindZone = false, nowMs = 500L))
    }
}
