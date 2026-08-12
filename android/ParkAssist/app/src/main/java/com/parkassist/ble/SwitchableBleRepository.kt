package com.parkassist.ble

import com.parkassist.protocol.SensorZone
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * 목업 모드 토글에 따라 [MultiZoneBleRepository] / [MockBleRepository]를 바꿔 끼운다.
 *
 * 화면과 서비스는 **이 객체 하나**를 계속 들고 있으면 되므로, 모드를 바꿔도 참조를
 * 다시 얻거나 앱을 재시작할 필요가 없다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableBleRepository(
    private val real: BleRepository,
    private val mock: MockBleRepository,
    scope: CoroutineScope,
) : BleRepository {

    private val activeRepository = MutableStateFlow(real)

    val isMockActive: Boolean get() = activeRepository.value === mock

    /** 목업 모드일 때만 의미가 있다. 아니면 null. */
    val mockControls: MockBleRepository? get() = mock.takeIf { isMockActive }

    private var started = false

    override val zones: StateFlow<Map<SensorZone, ZoneSnapshot>> =
        activeRepository.flatMapLatest { it.zones }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_ZONES)

    override val powerActive: StateFlow<Boolean> =
        activeRepository.flatMapLatest { it.powerActive }
            .stateIn(scope, SharingStarted.Eagerly, true)

    override fun start() {
        started = true
        activeRepository.value.start()
    }

    override fun stop() {
        started = false
        activeRepository.value.stop()
    }

    fun setMockEnabled(enabled: Boolean) {
        val target: BleRepository = if (enabled) mock else real
        val current = activeRepository.value
        if (target === current) return

        current.stop()
        activeRepository.value = target
        if (started) target.start()
    }

    override suspend fun sendThresholds(thresholds: Thresholds): Boolean =
        activeRepository.value.sendThresholds(thresholds)

    override suspend fun sendPower(active: Boolean): Boolean =
        activeRepository.value.sendPower(active)

    private companion object {
        val DEFAULT_ZONES: Map<SensorZone, ZoneSnapshot> =
            SensorZone.entries.associateWith { ZoneSnapshot() }
    }
}
