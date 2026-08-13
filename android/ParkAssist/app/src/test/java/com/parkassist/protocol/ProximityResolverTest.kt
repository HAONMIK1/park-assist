package com.parkassist.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사각지대 처리가 이 클래스의 존재 이유다.
 *
 * JSN-SR04T는 25cm 이내를 못 읽어 0xFF를 올린다. 그대로 두면 **가장 위험한 순간에**
 * 화면과 경보가 조용해진다.
 */
class ProximityResolverTest {

    private val thresholds = Thresholds.Default // 30 / 60 / 120

    @Test
    fun `유효한 값은 기준대로 단계가 매겨진다`() {
        val resolver = ProximityResolver()

        val state = resolver.resolve(frameOf(200, 100, 45, 20), 0L, thresholds, 0L)

        assertEquals(
            listOf(
                ProximityLevel.NONE,
                ProximityLevel.CAUTION,
                ProximityLevel.WARN,
                ProximityLevel.DANGER,
            ),
            state.channels.map { it.level },
        )
        assertEquals(20, state.nearestCm)
        assertEquals(ProximityLevel.DANGER, state.worst)
        assertFalse(state.anyBlindZone)
    }

    @Test
    fun `가까운 값을 보다가 측정 실패로 바뀌면 사각지대로 본다`() {
        val resolver = ProximityResolver()
        resolver.resolve(frameOf(28, null, null, null), 0L, thresholds, 0L)

        val state = resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, 100L)

        assertEquals(ProximityLevel.CRITICAL, state.channels[0].level)
        assertTrue(state.channels[0].blindZone)
        assertTrue(state.anyBlindZone)
        // 값 자체는 없다 — 숫자를 지어내지 않는다.
        assertEquals(null, state.nearestCm)
    }

    @Test
    fun `멀리서 측정이 안 되는 건 사각지대가 아니다`() {
        val resolver = ProximityResolver()
        resolver.resolve(frameOf(200, null, null, null), 0L, thresholds, 0L)

        val state = resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, 100L)

        assertEquals(ProximityLevel.NONE, state.channels[0].level)
        assertFalse(state.anyBlindZone)
    }

    @Test
    fun `한 번도 측정된 적 없는 채널은 사각지대가 아니다`() {
        val resolver = ProximityResolver()

        val state = resolver.resolve(frameOf(null, null, null, null), 0L, thresholds, 0L)

        assertFalse(state.anyBlindZone)
        assertEquals(ProximityLevel.NONE, state.worst)
    }

    @Test
    fun `사각지대 유지는 시간이 지나면 풀린다`() {
        val resolver = ProximityResolver(holdMs = 2_000L)
        resolver.resolve(frameOf(28, null, null, null), 0L, thresholds, 0L)

        val held = resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, 1_900L)
        val expired = resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, 2_500L)

        assertTrue(held.anyBlindZone)
        assertFalse(expired.anyBlindZone)
    }

    @Test
    fun `같은 프레임을 여러 번 넣어도 유지 시간이 연장되지 않는다`() {
        // 화면 갱신 티커가 같은 프레임으로 계속 호출한다. 그때마다 유지 시간이
        // 늘어나면 사각지대 표시가 영영 안 풀린다.
        val resolver = ProximityResolver(holdMs = 2_000L)
        resolver.resolve(frameOf(28, null, null, null), 0L, thresholds, 0L)

        var now = 100L
        while (now < 1_900L) {
            resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, now)
            now += 100L
        }
        val expired = resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, 2_500L)

        assertFalse(expired.anyBlindZone)
    }

    @Test
    fun `다시 유효한 값이 오면 사각지대가 즉시 해제된다`() {
        val resolver = ProximityResolver()
        resolver.resolve(frameOf(28, null, null, null), 0L, thresholds, 0L)
        resolver.resolve(frameOf(null, null, null, null), 100L, thresholds, 100L)

        val recovered = resolver.resolve(frameOf(80, null, null, null), 200L, thresholds, 200L)

        assertFalse(recovered.anyBlindZone)
        assertEquals(ProximityLevel.CAUTION, recovered.channels[0].level)
    }

    /** null = 측정 실패(0xFF) */
    private fun frameOf(vararg values: Int?): SensorFrame =
        SensorFrame(values.map { cm -> cm?.let { Distance.ofCm(it) } ?: Distance.Invalid })
}
