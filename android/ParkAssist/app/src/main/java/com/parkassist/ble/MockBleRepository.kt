package com.parkassist.ble

import android.os.SystemClock
import com.parkassist.protocol.Distance
import com.parkassist.protocol.SensorFrame
import com.parkassist.protocol.SensorZone
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 목업 구현 — 실제 기기 없이 UI 전체를 굴린다. 전방·후방 두 존을 모두 만든다.
 *
 * 설정 화면의 목업 모드 토글로 [MultiZoneBleRepository]와 바꿔 낀다.
 *
 * ## 후방(PARK-01) — 주차 시나리오, 26초 주기
 * 물체 접근 → 근접 유지 → 후퇴. 도중에 사각지대(25cm 이내) 진입으로 `0xFF`가 뜨고,
 * CH2는 주기적으로 측정에 실패한다(고장 센서 표시 확인용).
 *
 * ## 전방(PARK-02) — 주행 시나리오, 18초 주기
 * 평소에는 2m 밖에 있다가 1.2초 만에 200cm → 50cm로 좁혀 온다(약 125cm/s).
 * 주행 모드의 급접근 경보([com.parkassist.protocol.ApproachDetector])를 실기기 없이
 * 검증하기 위한 것이다.
 *
 * [simulateDisconnect]로 재연결 UI도 확인할 수 있다.
 */
class MockBleRepository(private val scope: CoroutineScope) : BleRepository {

    private val _zones = MutableStateFlow(
        SensorZone.entries.associateWith { ZoneSnapshot() }
    )
    override val zones: StateFlow<Map<SensorZone, ZoneSnapshot>> = _zones.asStateFlow()

    private val _powerActive = MutableStateFlow(true)
    override val powerActive: StateFlow<Boolean> = _powerActive.asStateFlow()

    /** 기기에 저장됐다고 가정하는 값. 실제로 쓰이진 않고 write 성공만 흉내 낸다. */
    private var storedThresholds = Thresholds.Default

    private var sessionJob: Job? = null
    private var running = false
    private var reconnectAttempt = 0

    override fun start() {
        if (running) return
        running = true
        reconnectAttempt = 0
        launchSession(initialDelayMs = 0L)
    }

    override fun stop() {
        running = false
        sessionJob?.cancel()
        sessionJob = null
        _zones.value = SensorZone.entries.associateWith { ZoneSnapshot() }
    }

    /** 연결이 끊긴 상황을 만든다. 재연결 UI 확인용. */
    fun simulateDisconnect() {
        if (!running) return
        sessionJob?.cancel()

        reconnectAttempt += 1
        val delayMs = ReconnectPolicy().delayFor(reconnectAttempt)
        updateAll { it.copy(connection = ConnectionState.Reconnecting(reconnectAttempt, delayMs), telemetry = null) }
        launchSession(initialDelayMs = delayMs)
    }

    private fun launchSession(initialDelayMs: Long) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)

            updateAll { it.copy(connection = ConnectionState.Scanning) }
            delay(SCAN_MS)
            updateAll { it.copy(connection = ConnectionState.Connecting) }
            delay(CONNECT_MS)
            updateAll { it.copy(connection = ConnectionState.Connected) }
            reconnectAttempt = 0

            val startedAt = SystemClock.elapsedRealtime()
            while (coroutineContext.isActive) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - startedAt
                val battery = (BATTERY_START - elapsed / BATTERY_DRAIN_MS).toInt().coerceIn(5, 100)

                _zones.value = SensorZone.entries.associateWith { zone ->
                    ZoneSnapshot(
                        connection = ConnectionState.Connected,
                        telemetry = Telemetry(generate(zone, elapsed), now),
                        // 앞뒤 기기의 소모가 같을 리 없으니 조금 다르게 둔다.
                        batteryPercent = if (zone == SensorZone.FRONT) battery else battery - 6,
                    )
                }
                delay(FRAME_INTERVAL_MS) // 실기기와 같은 10Hz
            }
        }
    }

    private fun updateAll(transform: (ZoneSnapshot) -> ZoneSnapshot) {
        _zones.value = _zones.value.mapValues { (_, snapshot) -> transform(snapshot) }
    }

    override suspend fun sendThresholds(thresholds: Thresholds): Boolean {
        if (!thresholds.isValid) return false
        delay(WRITE_LATENCY_MS)
        storedThresholds = thresholds
        return _zones.value.anyConnected
    }

    override suspend fun sendPower(active: Boolean): Boolean {
        delay(WRITE_LATENCY_MS)
        if (!_zones.value.anyConnected) return false
        _powerActive.value = active
        return true
    }

    // ── 데이터 생성 ────────────────────────────────────────────────

    private fun generate(zone: SensorZone, elapsedMs: Long): SensorFrame {
        // 대기 모드에서는 기기가 측정을 멈추므로 전 채널 측정 실패로 본다.
        if (!_powerActive.value) return SensorFrame.Empty

        val base = when (zone) {
            SensorZone.REAR -> parkingApproach(elapsedMs)
            SensorZone.FRONT -> drivingApproach(elapsedMs)
        }

        return SensorFrame(
            List(SensorFrame.CHANNEL_COUNT) { ch ->
                val cm = base + CHANNEL_OFFSETS_CM[ch] + noise(elapsedMs, ch)
                when {
                    zone == SensorZone.REAR && isFaulty(elapsedMs, ch) -> Distance.Invalid
                    cm < BLIND_ZONE_CM -> Distance.Invalid // 사각지대 → 0xFF
                    cm > Distance.MAX_CM -> Distance.ofCm(Distance.MAX_CM)
                    else -> Distance.ofCm(cm.roundToInt())
                }
            }
        )
    }

    /** 주차: 천천히 다가갔다가 물러난다. */
    private fun parkingApproach(elapsedMs: Long): Double {
        val t = (elapsedMs % PARKING_CYCLE_MS).toDouble() / PARKING_CYCLE_MS
        return when {
            t < 0.54 -> lerp(FAR_CM, CLOSE_CM, t / 0.54)
            t < 0.65 -> CLOSE_CM
            t < 0.92 -> lerp(CLOSE_CM, FAR_CM, (t - 0.65) / 0.27)
            else -> FAR_CM
        }
    }

    /** 주행: 평소엔 멀리 있다가 갑자기 훅 들어온다. */
    private fun drivingApproach(elapsedMs: Long): Double {
        val phase = elapsedMs % DRIVING_CYCLE_MS
        return when {
            phase < DRIVING_CRUISE_MS -> FAR_CM
            phase < DRIVING_CRUISE_MS + DRIVING_RUSH_MS -> lerp(
                FAR_CM,
                DRIVING_MIN_CM,
                (phase - DRIVING_CRUISE_MS).toDouble() / DRIVING_RUSH_MS,
            )

            phase < DRIVING_CRUISE_MS + DRIVING_RUSH_MS + DRIVING_HOLD_MS -> DRIVING_MIN_CM
            else -> lerp(
                DRIVING_MIN_CM,
                FAR_CM,
                (phase - DRIVING_CRUISE_MS - DRIVING_RUSH_MS - DRIVING_HOLD_MS).toDouble() /
                    (DRIVING_CYCLE_MS - DRIVING_CRUISE_MS - DRIVING_RUSH_MS - DRIVING_HOLD_MS),
            )
        }
    }

    /** 결정적인 흔들림 — 난수를 쓰지 않아 재현 가능하다. */
    private fun noise(elapsedMs: Long, ch: Int): Double =
        sin(elapsedMs / 137.0 + ch * 1.7) * 1.5

    /** CH2가 주기적으로 측정에 실패한다 (고장/미연결 센서 표시 확인용). */
    private fun isFaulty(elapsedMs: Long, ch: Int): Boolean =
        ch == FAULTY_CHANNEL && (elapsedMs % FAULT_PERIOD_MS) < FAULT_DURATION_MS

    private fun lerp(from: Double, to: Double, t: Double): Double =
        from + (to - from) * t.coerceIn(0.0, 1.0)

    private companion object {
        const val FRAME_INTERVAL_MS = 100L

        const val FAR_CM = 250.0
        const val CLOSE_CM = 8.0

        /** JSN-SR04T 사각지대. 이보다 가까우면 기기가 0xFF를 올린다. */
        const val BLIND_ZONE_CM = 25.0

        const val PARKING_CYCLE_MS = 26_000L

        // 200cm → 50cm 를 1.2초에 = 약 125cm/s. 급접근 기준(60cm/s)을 넉넉히 넘긴다.
        const val DRIVING_CYCLE_MS = 18_000L
        const val DRIVING_CRUISE_MS = 9_000L
        const val DRIVING_RUSH_MS = 1_200L
        const val DRIVING_HOLD_MS = 2_000L
        const val DRIVING_MIN_CM = 50.0

        /** 물체가 정면이 아니라 살짝 우측에 있는 상황 — CH3가 가장 가깝다. */
        val CHANNEL_OFFSETS_CM = doubleArrayOf(38.0, 12.0, 0.0, 26.0)

        const val FAULTY_CHANNEL = 1
        const val FAULT_PERIOD_MS = 7_000L
        const val FAULT_DURATION_MS = 800L

        const val SCAN_MS = 1_200L
        const val CONNECT_MS = 400L
        const val WRITE_LATENCY_MS = 120L

        const val BATTERY_START = 87L
        const val BATTERY_DRAIN_MS = 60_000L
    }
}
