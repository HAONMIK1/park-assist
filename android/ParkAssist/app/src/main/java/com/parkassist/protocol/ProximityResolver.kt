package com.parkassist.protocol

/** 채널 한 개의 표시 상태. */
data class ChannelState(
    val distance: Distance,
    val level: ProximityLevel,
    /** 사각지대 진입으로 판단해 최고 단계를 유지 중인지 */
    val blindZone: Boolean,
)

/** 화면 한 장을 그리는 데 필요한 전부. */
data class ProximityState(
    val channels: List<ChannelState>,
    /** 유효값 중 최단 거리. 전부 측정 실패면 null. */
    val nearestCm: Int?,
    val worst: ProximityLevel,
    /** 하나라도 사각지대 유지 중인지 → 숫자 대신 "매우 가까움"을 띄운다. */
    val anyBlindZone: Boolean,
) {
    companion object {
        val Empty = ProximityState(
            channels = List(SensorFrame.CHANNEL_COUNT) {
                ChannelState(Distance.Invalid, ProximityLevel.NONE, blindZone = false)
            },
            nearestCm = null,
            worst = ProximityLevel.NONE,
            anyBlindZone = false,
        )
    }
}

/**
 * 프레임 → 채널별 표시 상태.
 *
 * ## 사각지대 처리
 *
 * JSN-SR04T는 약 25cm 이내를 못 읽어 `0xFF`를 올린다. 기본 위험 기준이 30cm이므로
 * **가장 위험한 순간에 화면의 숫자가 사라지는** 문제가 생긴다.
 *
 * 그래서 "가까운 값을 보다가 방금 측정 실패로 바뀐" 경우는 멀어진 게 아니라 사각지대에
 * 들어간 것으로 보고 [ProximityLevel.CRITICAL]을 [holdMs] 동안 유지한다.
 * 그냥 멀리서 측정이 안 되는 경우(미연결 센서 등)와는 구분된다.
 *
 * 상태를 들고 있지만 시간은 전부 인자로 받으므로 테스트에서 결정적으로 검증된다.
 *
 * @param holdMs 마지막 유효 측정 이후 CRITICAL을 유지할 시간
 * @param marginCm 위험 기준보다 이만큼 더 멀리서 끊겨도 사각지대로 본다
 */
class ProximityResolver(
    private val holdMs: Long = 2_000L,
    private val marginCm: Int = 10,
) {
    private val lastValidCm = IntArray(SensorFrame.CHANNEL_COUNT) { NO_READING }
    private val lastValidAtMs = LongArray(SensorFrame.CHANNEL_COUNT)

    fun reset() {
        lastValidCm.fill(NO_READING)
        lastValidAtMs.fill(0L)
    }

    /**
     * @param frameAtMs 이 프레임을 **받은** 시각. 유효값 기록에 쓴다.
     * @param nowMs 지금 시각. 유지 시간 만료 판정에 쓴다.
     *
     * 두 시각을 나눈 덕분에 같은 프레임으로 여러 번 호출해도 결과가 흔들리지 않는다
     * (화면 갱신 티커가 매번 호출한다).
     */
    fun resolve(
        frame: SensorFrame,
        frameAtMs: Long,
        thresholds: Thresholds,
        nowMs: Long,
    ): ProximityState {
        val channels = List(SensorFrame.CHANNEL_COUNT) { ch ->
            resolveChannel(ch, frame.distances[ch], frameAtMs, thresholds, nowMs)
        }
        return ProximityState(
            channels = channels,
            nearestCm = channels.mapNotNull { it.distance.cm }.minOrNull(),
            worst = channels.maxOf { it.level },
            anyBlindZone = channels.any { it.blindZone },
        )
    }

    private fun resolveChannel(
        ch: Int,
        distance: Distance,
        frameAtMs: Long,
        thresholds: Thresholds,
        nowMs: Long,
    ): ChannelState {
        val cm = distance.cm
        if (cm != null) {
            lastValidCm[ch] = cm
            lastValidAtMs[ch] = frameAtMs
            return ChannelState(distance, thresholds.levelFor(cm), blindZone = false)
        }

        val last = lastValidCm[ch]
        if (last == NO_READING) {
            return ChannelState(distance, ProximityLevel.NONE, blindZone = false)
        }

        val wasClose = last <= thresholds.nearCm + marginCm
        val stillHolding = nowMs - lastValidAtMs[ch] < holdMs
        return if (wasClose && stillHolding) {
            ChannelState(distance, ProximityLevel.CRITICAL, blindZone = true)
        } else {
            ChannelState(distance, ProximityLevel.NONE, blindZone = false)
        }
    }

    private companion object {
        const val NO_READING = -1
    }
}
