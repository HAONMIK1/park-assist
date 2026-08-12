package com.parkassist.ble

import com.parkassist.protocol.SensorFrame
import com.parkassist.protocol.SensorZone
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.flow.StateFlow

/** 측정 프레임 + 받은 시각. 시각은 `SystemClock.elapsedRealtime()` 기준이다. */
data class Telemetry(val frame: SensorFrame, val receivedAtMs: Long)

/** 존(기기) 한 대의 상태. */
data class ZoneSnapshot(
    val connection: ConnectionState = ConnectionState.Idle,
    val telemetry: Telemetry? = null,
    /** 기기가 Battery Service를 제공하지 않으면 null(= 알 수 없음). */
    val batteryPercent: Int? = null,
)

/**
 * 기기와의 통신. **Real / Mock 두 구현의 이음매다.**
 *
 * UI와 서비스는 이 인터페이스만 본다. `BluetoothDevice`, `BluetoothGatt` 같은
 * Real 전용 타입이 이 위로 새어 나가면 목업 모드가 깨진다.
 *
 * 존이 여러 개인 것을 인터페이스에 박아 둔 덕분에, 나중에 "기기 1대 + 8센서" 방식으로
 * 바꾸더라도 이 계층 아래만 갈아 끼우면 된다.
 */
interface BleRepository {

    /** 존별 상태. **모든 [SensorZone] 키가 항상 들어 있다.** */
    val zones: StateFlow<Map<SensorZone, ZoneSnapshot>>

    /**
     * 기기가 작동 중인지.
     *
     * 기기는 명령에 ACK를 주지 않으므로(docs/ble-protocol.md 7절) 이 값은 앱이 아는
     * 마지막 상태다. 실제 기기 상태와 어긋날 수 있다.
     */
    val powerActive: StateFlow<Boolean>

    /** 스캔 → 연결 → 자동 재연결 루프 시작. 이미 시작했으면 무시한다. */
    fun start()

    /** 모든 연결을 끊고 재시도를 중단한다. */
    fun stop()

    /**
     * `0xBB`를 **모든 존에** 전송.
     * @return 연결된 기기 전부가 받았으면 true.
     */
    suspend fun sendThresholds(thresholds: Thresholds): Boolean

    /** `0xCC`를 모든 존에 전송. */
    suspend fun sendPower(active: Boolean): Boolean
}

/** 존 중 하나라도 연결돼 있으면 true. */
val Map<SensorZone, ZoneSnapshot>.anyConnected: Boolean
    get() = values.any { it.connection == ConnectionState.Connected }

/** 화면 상단에 대표로 띄울 상태. 가장 "덜 좋은" 상태를 보여준다. */
val Map<SensorZone, ZoneSnapshot>.summaryConnection: ConnectionState
    get() {
        val states = SensorZone.entries.map { this[it]?.connection ?: ConnectionState.Idle }
        return states.minByOrNull { it.severity } ?: ConnectionState.Idle
    }

/** 두 기기 배터리 중 낮은 쪽. 둘 다 모르면 null. */
val Map<SensorZone, ZoneSnapshot>.lowestBattery: Int?
    get() = values.mapNotNull { it.batteryPercent }.minOrNull()

/** 작을수록 나쁜 상태. [summaryConnection] 정렬에만 쓴다. */
private val ConnectionState.severity: Int
    get() = when (this) {
        ConnectionState.PermissionRequired -> 0
        ConnectionState.BluetoothOff -> 1
        is ConnectionState.Disconnected -> 2
        is ConnectionState.Reconnecting -> 3
        ConnectionState.Idle -> 4
        ConnectionState.Scanning -> 5
        ConnectionState.Connecting -> 6
        ConnectionState.Connected -> 7
    }
