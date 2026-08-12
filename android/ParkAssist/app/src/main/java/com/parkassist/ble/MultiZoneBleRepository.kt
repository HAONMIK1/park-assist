package com.parkassist.ble

import android.content.Context
import com.parkassist.protocol.SensorZone
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 실제 기기 구현 — 전방(PARK-02)·후방(PARK-01) **두 대에 동시에** 연결한다.
 *
 * 존마다 [DeviceConnection]을 하나씩 두고 각자 스캔·연결·재연결하게 둔다.
 * 한쪽이 꺼지거나 배터리가 나가도 다른 쪽은 그대로 동작한다(ADR 0004).
 */
class MultiZoneBleRepository(
    context: Context,
    private val scope: CoroutineScope,
) : BleRepository {

    private val connections: Map<SensorZone, DeviceConnection> =
        SensorZone.entries.associateWith { zone -> DeviceConnection(context, zone, scope) }

    /** 연결 직후 각 기기에 밀어 넣을 거리 기준. [com.parkassist.di.AppContainer]가 주입한다. */
    var thresholdsProvider: (() -> Thresholds)? = null
        set(value) {
            field = value
            connections.values.forEach { it.thresholdsProvider = value }
        }

    override val zones: StateFlow<Map<SensorZone, ZoneSnapshot>> =
        combine(
            connections.map { (zone, connection) ->
                combine(
                    connection.connectionState,
                    connection.telemetry,
                    connection.batteryPercent,
                ) { state, telemetry, battery ->
                    zone to ZoneSnapshot(state, telemetry, battery)
                }
            }
        ) { entries -> entries.toMap() }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_ZONES)

    private val _powerActive = MutableStateFlow(true)
    override val powerActive: StateFlow<Boolean> = _powerActive.asStateFlow()

    override fun start() {
        connections.values.forEach { it.start() }
    }

    override fun stop() {
        connections.values.forEach { it.stop() }
    }

    override suspend fun sendThresholds(thresholds: Thresholds): Boolean =
        broadcast { it.sendThresholds(thresholds) }

    override suspend fun sendPower(active: Boolean): Boolean {
        val ok = broadcast { it.sendPower(active) }
        if (ok) _powerActive.value = active
        return ok
    }

    /**
     * 연결된 기기 **전부**에 보내고, 전부 성공해야 true.
     *
     * 아직 붙지 않은 기기는 대상에서 뺀다. 그쪽은 연결될 때
     * [DeviceConnection.pushInitialState]가 같은 값을 다시 밀어 넣는다.
     */
    private suspend fun broadcast(action: suspend (DeviceConnection) -> Boolean): Boolean =
        coroutineScope {
            val ready = connections.values.filter { it.isReady }
            if (ready.isEmpty()) return@coroutineScope false
            ready.map { async { action(it) } }.awaitAll().all { it }
        }

    private companion object {
        val DEFAULT_ZONES: Map<SensorZone, ZoneSnapshot> =
            SensorZone.entries.associateWith { ZoneSnapshot() }
    }
}
