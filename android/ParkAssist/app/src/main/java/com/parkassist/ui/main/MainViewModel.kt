package com.parkassist.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.parkassist.ble.BleRepository
import com.parkassist.ble.ConnectionState
import com.parkassist.ble.anyConnected
import com.parkassist.ble.lowestBattery
import com.parkassist.ble.summaryConnection
import com.parkassist.data.SettingsStore
import com.parkassist.di.AppContainer
import com.parkassist.domain.ProximityMonitor
import com.parkassist.domain.ZoneProximity
import com.parkassist.protocol.ChannelState
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.ProximityLevel
import com.parkassist.protocol.ProximityState
import com.parkassist.protocol.SensorZone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val mode: DriveMode = DriveMode.PARKING,
    val summaryConnection: ConnectionState = ConnectionState.Idle,
    val anyConnected: Boolean = false,
    val zones: Map<SensorZone, ZoneProximity> = SensorZone.entries.associateWith { ZoneProximity() },
    val batteryPercent: Int? = null,
    val powerActive: Boolean = true,
    /** 경보를 유발한 방향. 없으면 null. */
    val alertZone: SensorZone? = null,
    /** 주행 모드 급접근 경보 중인지. */
    val urgent: Boolean = false,
) {
    fun channels(zone: SensorZone): List<ChannelState> =
        zones[zone]?.proximity?.channels ?: ProximityState.Empty.channels

    fun connection(zone: SensorZone): ConnectionState =
        zones[zone]?.connection ?: ConnectionState.Idle

    private val liveZones: List<ZoneProximity> get() = zones.values.filter { it.isLive }

    /** 앞뒤 통틀어 가장 가까운 거리. */
    val nearestCm: Int? get() = liveZones.mapNotNull { it.proximity.nearestCm }.minOrNull()

    val worst: ProximityLevel
        get() = liveZones.maxOfOrNull { it.proximity.worst } ?: ProximityLevel.NONE

    val anyBlindZone: Boolean get() = liveZones.any { it.proximity.anyBlindZone }

    /** 연결은 됐는데 어느 존에서도 패킷이 안 들어오는 상태. */
    val isStale: Boolean get() = anyConnected && liveZones.isEmpty()
}

class MainViewModel(
    private val repository: BleRepository,
    private val settingsStore: SettingsStore,
    monitor: ProximityMonitor,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        monitor.state,
        repository.zones,
        repository.powerActive,
    ) { monitorState, zoneSnapshots, powerActive ->
        MainUiState(
            mode = monitorState.mode,
            summaryConnection = zoneSnapshots.summaryConnection,
            anyConnected = zoneSnapshots.anyConnected,
            zones = monitorState.zones,
            batteryPercent = zoneSnapshots.lowestBattery,
            powerActive = powerActive,
            alertZone = monitorState.alertZone,
            urgent = monitorState.urgent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MainUiState())

    fun togglePower() {
        viewModelScope.launch {
            repository.sendPower(!repository.powerActive.value)
        }
    }

    fun setMode(mode: DriveMode) {
        viewModelScope.launch { settingsStore.setDriveMode(mode) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    container.bleRepository,
                    container.settingsStore,
                    container.proximityMonitor,
                )
            }
        }
    }
}
