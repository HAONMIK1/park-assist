package com.parkassist.protocol

/**
 * `0xAA` 패킷 한 개의 내용.
 *
 * `d1`~`d4` = CH1~CH4이고, 물리 배치는 차량 뒤에서 봤을 때 **좌 → 우** 순서다.
 * 화면의 부채꼴 4개도 같은 순서로 그린다.
 */
data class SensorFrame(val distances: List<Distance>) {

    init {
        require(distances.size == CHANNEL_COUNT) {
            "센서 채널은 ${CHANNEL_COUNT}개여야 한다 (받음: ${distances.size})"
        }
    }

    /** 유효한 값 중 가장 가까운 거리. 전부 측정 실패면 null. */
    val nearestCm: Int? get() = distances.mapNotNull { it.cm }.minOrNull()

    companion object {
        const val CHANNEL_COUNT = 4

        val Empty = SensorFrame(List(CHANNEL_COUNT) { Distance.Invalid })
    }
}
