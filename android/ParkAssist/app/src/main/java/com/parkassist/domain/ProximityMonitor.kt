package com.parkassist.domain

import android.os.SystemClock
import com.parkassist.ble.BleConstants
import com.parkassist.ble.BleRepository
import com.parkassist.ble.ConnectionState
import com.parkassist.ble.ZoneSnapshot
import com.parkassist.data.SettingsStore
import com.parkassist.protocol.AlarmCurve
import com.parkassist.protocol.AlarmTone
import com.parkassist.protocol.ApproachDetector
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.ProximityResolver
import com.parkassist.protocol.ProximityState
import com.parkassist.protocol.SensorZone
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** 존(기기) 하나의 표시 상태. */
data class ZoneProximity(
    val connection: ConnectionState = ConnectionState.Idle,
    val proximity: ProximityState = ProximityState.Empty,
    /** 연결은 됐는데 패킷이 끊긴 상태. 연결 끊김과는 다르다. */
    val isStale: Boolean = true,
    /** 주행 모드 급접근 감지 결과. */
    val urgentApproach: Boolean = false,
) {
    val isLive: Boolean get() = connection == ConnectionState.Connected && !isStale
}

data class MonitorState(
    val mode: DriveMode = DriveMode.PARKING,
    val thresholds: Thresholds = Thresholds.Default,
    val zones: Map<SensorZone, ZoneProximity> = SensorZone.entries.associateWith { ZoneProximity() },
    /** 지금 내야 할 경고음. 울릴 필요가 없으면 null. */
    val tone: AlarmTone? = null,
    /** 내비게이션 음성을 눌러서라도 들려야 하는 경보인지. */
    val urgent: Boolean = false,
    /** 경보를 유발한 존. 화면에서 방향을 표시하는 데 쓴다. */
    val alertZone: SensorZone? = null,
) {
    fun zone(zone: SensorZone): ZoneProximity = zones[zone] ?: ZoneProximity()

    companion object {
        val Empty = MonitorState()
    }
}

/**
 * 측정 프레임 + 설정 → 화면과 소리가 함께 쓰는 상태 한 벌.
 *
 * 화면(ViewModel)과 Foreground Service가 **같은 값**을 봐야 표시와 소리가 어긋나지
 * 않으므로 여기서 한 번만 계산한다.
 *
 * ## 모드별 경보 규칙
 *
 * | 모드 | 기준 | 소리 |
 * | --- | --- | --- |
 * | 주차 | 거리 | [AlarmCurve] — 가까울수록 연속적으로 세짐 |
 * | 주행 | **접근 속도** | [ApproachDetector]가 걸릴 때만 [AlarmCurve.Urgent] |
 *
 * 주행 중에는 앞뒤 차가 1~2m에 있는 게 정상이라 거리로는 경보를 낼 수 없다.
 *
 * 프레임이 멈춰도 사각지대 유지 시간이 만료되어야 하므로, 프레임뿐 아니라 티커에도
 * 반응해서 다시 계산한다.
 */
class ProximityMonitor(
    repository: BleRepository,
    settingsStore: SettingsStore,
    scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val resolvers = SensorZone.entries.associateWith { ProximityResolver() }
    private val detectors = SensorZone.entries.associateWith { ApproachDetector() }

    val state: StateFlow<MonitorState> =
        combine(
            repository.zones,
            settingsStore.activeThresholds,
            settingsStore.driveMode,
            ticker(),
        ) { snapshots, thresholds, mode, now ->
            compute(snapshots, thresholds, mode, now)
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MonitorState.Empty)

    private fun compute(
        snapshots: Map<SensorZone, ZoneSnapshot>,
        thresholds: Thresholds,
        mode: DriveMode,
        now: Long,
    ): MonitorState {
        val zones = SensorZone.entries.associateWith { zone ->
            resolveZone(zone, snapshots[zone] ?: ZoneSnapshot(), thresholds, mode, now)
        }

        return when (mode) {
            DriveMode.PARKING -> parkingAlarm(zones, thresholds, mode)
            DriveMode.DRIVING -> drivingAlarm(zones, thresholds, mode)
        }
    }

    private fun resolveZone(
        zone: SensorZone,
        snapshot: ZoneSnapshot,
        thresholds: Thresholds,
        mode: DriveMode,
        now: Long,
    ): ZoneProximity {
        val telemetry = snapshot.telemetry
        val stale = telemetry == null ||
            now - telemetry.receivedAtMs > BleConstants.TELEMETRY_STALE_MS

        if (stale) {
            // 데이터가 끊긴 상태에서는 아무것도 주장하지 않는다. 소리도 멈춘다.
            resolvers.getValue(zone).reset()
            detectors.getValue(zone).reset()
            return ZoneProximity(snapshot.connection, ProximityState.Empty, isStale = true)
        }

        val proximity = resolvers.getValue(zone).resolve(
            frame = telemetry.frame,
            frameAtMs = telemetry.receivedAtMs,
            thresholds = thresholds,
            nowMs = now,
        )

        val detector = detectors.getValue(zone)
        val urgent = if (mode == DriveMode.DRIVING) {
            detector.update(proximity.nearestCm, proximity.anyBlindZone, now)
        } else {
            detector.reset()
            false
        }

        return ZoneProximity(snapshot.connection, proximity, isStale = false, urgentApproach = urgent)
    }

    /** 주차: 앞뒤 통틀어 가장 가까운 거리로 강도를 정한다. */
    private fun parkingAlarm(
        zones: Map<SensorZone, ZoneProximity>,
        thresholds: Thresholds,
        mode: DriveMode,
    ): MonitorState {
        val live = zones.filterValues { it.isLive }

        val blindZone = live.entries.firstOrNull { it.value.proximity.anyBlindZone }?.key
        val nearestEntry = live.entries
            .filter { it.value.proximity.nearestCm != null }
            .minByOrNull { it.value.proximity.nearestCm!! }

        val nearestCm = nearestEntry?.value?.proximity?.nearestCm
        val anyBlindZone = blindZone != null

        return MonitorState(
            mode = mode,
            thresholds = thresholds,
            zones = zones,
            tone = AlarmCurve.toneFor(nearestCm, anyBlindZone, thresholds),
            urgent = false,
            alertZone = blindZone ?: nearestEntry?.key,
        )
    }

    /** 주행: 급접근이 걸린 존이 있을 때만, 처음부터 최대 강도로 울린다. */
    private fun drivingAlarm(
        zones: Map<SensorZone, ZoneProximity>,
        thresholds: Thresholds,
        mode: DriveMode,
    ): MonitorState {
        // 앞뒤가 동시에 걸리면 전방을 먼저 알린다 — 운전자가 대응할 수 있는 쪽이다.
        val alerting = ALERT_PRIORITY.firstOrNull { zones[it]?.urgentApproach == true }

        return MonitorState(
            mode = mode,
            thresholds = thresholds,
            zones = zones,
            tone = if (alerting != null) AlarmCurve.Urgent else null,
            urgent = alerting != null,
            alertZone = alerting,
        )
    }

    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    private companion object {
        /** 수신 주기(100ms)와 맞춘다. */
        const val TICK_MS = 100L
        const val STOP_TIMEOUT_MS = 5_000L

        /** 주행 경보에서 먼저 알릴 방향. */
        val ALERT_PRIORITY = listOf(SensorZone.FRONT, SensorZone.REAR)
    }
}
