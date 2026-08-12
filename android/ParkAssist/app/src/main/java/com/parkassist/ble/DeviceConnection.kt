package com.parkassist.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.parkassist.protocol.ProtocolCodec
import com.parkassist.protocol.SensorZone
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 기기 **한 대**와의 BLE 연결. Android 네이티브 API만 쓴다.
 *
 * 앱은 이걸 두 개 만들어 전방(PARK-02)·후방(PARK-01)에 동시에 붙는다
 * ([MultiZoneBleRepository]). 두 기기가 같은 서비스 UUID를 쓰므로 **광고 이름으로**
 * 자기 짝을 가려낸다.
 *
 * 권한은 호출 전에 [BlePermissions.allGranted]로 확인하고, 없으면
 * [ConnectionState.PermissionRequired]로 넘어간다. 그래서 `MissingPermission`을 억제한다.
 */
@SuppressLint("MissingPermission")
class DeviceConnection(
    context: Context,
    private val zone: SensorZone,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val tag = "DeviceConnection/${zone.deviceName}"

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    /** 알림까지 켜져서 명령을 보낼 수 있는 상태인지. */
    val isReady: Boolean get() = _connectionState.value == ConnectionState.Connected && rxChar != null

    /**
     * 연결 직후 기기에 밀어 넣을 거리 기준.
     *
     * 기기에 저장된 값을 읽어올 방법이 없어서(docs/ble-protocol.md 7절) 앱에 저장된
     * 값을 진실로 본다.
     */
    var thresholdsProvider: (() -> Thresholds)? = null

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    @Volatile private var running = false
    @Volatile private var scanning = false

    private var attempt = 0
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var receiverRegistered = false

    /**
     * 다음 연결에서 전원을 켤지.
     *
     * 안전 장치의 기본 상태는 켜짐이어야 하지만(ADR 0001), 사용자가 방금 끈 것을
     * 재연결이 되살리면 끄기 버튼이 무의미해진다. 그래서 앱/서비스 시작 시 한 번만 켠다.
     */
    private var forcePowerOnNextConnect = false

    // ── GATT 작업 큐 ────────────────────────────────────────────────
    // BLE는 한 번에 하나의 GATT 작업만 허용한다. 겹쳐 부르면 조용히 실패한다.

    private class GattOp(
        val name: String,
        val exec: () -> Boolean,
        val result: CompletableDeferred<Boolean>? = null,
    )

    private val opLock = Any()
    private val opQueue = ArrayDeque<GattOp>()
    private var opInFlight: GattOp? = null

    private fun enqueue(op: GattOp) {
        synchronized(opLock) {
            opQueue.addLast(op)
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        while (opInFlight == null) {
            val next = opQueue.removeFirstOrNull() ?: return
            opInFlight = next
            val started = try {
                next.exec()
            } catch (t: Throwable) {
                Log.w(tag, "GATT 작업 시작 실패: ${next.name}", t)
                false
            }
            if (started) return
            opInFlight = null
            next.result?.complete(false)
        }
    }

    private fun completeOp(success: Boolean) {
        synchronized(opLock) {
            val op = opInFlight
            opInFlight = null
            op?.result?.complete(success)
            pumpLocked()
        }
    }

    private fun abandonOp(op: GattOp) {
        synchronized(opLock) {
            if (opInFlight === op) {
                opInFlight = null
                pumpLocked()
            } else {
                opQueue.remove(op)
            }
        }
    }

    private fun clearOps() {
        synchronized(opLock) {
            opInFlight?.result?.complete(false)
            opInFlight = null
            opQueue.forEach { it.result?.complete(false) }
            opQueue.clear()
        }
    }

    // ── 수명 주기 ──────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        forcePowerOnNextConnect = true
        attempt = 0
        registerAdapterReceiver()
        beginScan()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        stopScan()
        clearOps()
        closeGatt()
        unregisterAdapterReceiver()
        _telemetry.value = null
        _batteryPercent.value = null
        _connectionState.value = ConnectionState.Idle
    }

    // ── 스캔 ──────────────────────────────────────────────────────

    private fun beginScan() {
        reconnectJob?.cancel()

        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ConnectionState.BluetoothOff
            return
        }
        if (!BlePermissions.allGranted(appContext)) {
            _connectionState.value = ConnectionState.PermissionRequired
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            scheduleReconnect(null)
            return
        }

        // 이름 필터가 통하는 기기는 이걸로 바로 걸러지고, 이름을 광고에 싣지 않는
        // 펌웨어 빌드는 서비스 UUID로 걸린 뒤 [matchesZone]에서 다시 확인된다.
        val filters = listOf(
            ScanFilter.Builder().setDeviceName(zone.deviceName).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        _connectionState.value = ConnectionState.Scanning
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (t: Throwable) {
            Log.w(tag, "스캔 시작 실패", t)
            scanning = false
            scheduleReconnect(null)
            return
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(BleConstants.SCAN_TIMEOUT_MS)
            if (scanning) {
                stopScan()
                scheduleReconnect(null)
            }
        }
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            Log.w(tag, "스캔 중단 실패", t)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return // 이미 하나 잡았다 — 중복 콜백 무시
            if (!matchesZone(result)) return // 반대편 기기다
            val device = result.device ?: return
            stopScan()
            connectTo(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(tag, "스캔 실패: $errorCode")
            scanning = false
            scanTimeoutJob?.cancel()
            scheduleReconnect(null)
        }
    }

    /**
     * 전방/후방 기기가 같은 서비스 UUID를 쓰므로 **이름으로 반드시 다시 확인한다.**
     * 이걸 빼면 두 연결이 같은 기기를 잡는다.
     */
    private fun matchesZone(result: ScanResult): Boolean {
        val advertised = result.scanRecord?.deviceName
        if (advertised != null) return advertised == zone.deviceName

        val cached = runCatching { result.device?.name }.getOrNull()
        return cached == zone.deviceName
    }

    // ── 연결 ──────────────────────────────────────────────────────

    private fun connectTo(device: BluetoothDevice) {
        closeGatt()
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        rxChar = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun scheduleReconnect(status: Int?) {
        if (!running) {
            _connectionState.value = ConnectionState.Disconnected(status)
            return
        }
        attempt += 1
        val delayMs = reconnectPolicy.delayFor(attempt)
        _connectionState.value = ConnectionState.Reconnecting(attempt, delayMs)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (running) beginScan()
        }
    }

    private fun handleDisconnect(status: Int?) {
        clearOps()
        closeGatt()
        _telemetry.value = null
        _batteryPercent.value = null
        scheduleReconnect(status)
    }

    // ── GATT 콜백 ──────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS -> {
                    attempt = 0
                    _connectionState.value = ConnectionState.Connecting
                    g.discoverServices()
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> handleDisconnect(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                g.disconnect()
                return
            }

            val service = g.getService(BleConstants.SERVICE_UUID)
            val tx = service?.getCharacteristic(BleConstants.TX_CHAR_UUID)
            if (tx == null) {
                Log.w(tag, "서비스/TX 캐릭터리스틱을 찾지 못했다")
                g.disconnect()
                return
            }
            rxChar = service.getCharacteristic(BleConstants.RX_CHAR_UUID)

            // TX 알림이 최우선. 이게 켜져야 데이터가 온다.
            enqueue(GattOp("enable TX notify") { enableNotifications(g, tx) })

            // 배터리는 표준 서비스가 있을 때만. 없으면 "알 수 없음"으로 남는다.
            g.getService(BleConstants.BATTERY_SERVICE_UUID)
                ?.getCharacteristic(BleConstants.BATTERY_LEVEL_CHAR_UUID)
                ?.let { battery ->
                    enqueue(GattOp("read battery") { g.readCharacteristic(battery) })
                    if (battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                        enqueue(GattOp("enable battery notify") { enableNotifications(g, battery) })
                    }
                }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            val isTxCccd = descriptor.characteristic?.uuid == BleConstants.TX_CHAR_UUID
            completeOp(ok)

            if (!isTxCccd) return
            if (ok) {
                // CCCD write가 끝나야 비로소 알림을 받을 수 있다. 여기가 진짜 "연결됨"이다.
                _connectionState.value = ConnectionState.Connected
                pushInitialState()
            } else {
                Log.w(tag, "CCCD write 실패 — 알림을 못 받으므로 재연결한다")
                g.disconnect()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ — 값이 인자로 온다.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid, value)
        }

        // API 32 이하 — 값을 characteristic.value로 읽는다.
        // 33 이상에서는 위 오버로드만 호출되지만, 제조사 변형을 대비해 한 번 더 막는다.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleNotification(characteristic.uuid, characteristic.value ?: return)
        }

        // API 33+
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleNotification(characteristic.uuid, value)
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 32 이하
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.let { handleNotification(characteristic.uuid, it) }
            }
            completeOp(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * `setCharacteristicNotification()` **만으로는 알림이 오지 않는다.**
     * CCCD에 ENABLE_NOTIFICATION_VALUE를 써야 한다.
     */
    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) return false
        val cccd = ch.getDescriptor(BleConstants.CCCD_UUID) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.TX_CHAR_UUID -> {
                // 체크섬이 어긋나면 null → 조용히 버린다. 부분 갱신은 하지 않는다.
                val frame = ProtocolCodec.parseTelemetry(value) ?: return
                _telemetry.value = Telemetry(frame, SystemClock.elapsedRealtime())
            }

            BleConstants.BATTERY_LEVEL_CHAR_UUID -> {
                val percent = value.firstOrNull()?.toInt()?.and(0xFF) ?: return
                _batteryPercent.value = percent.coerceIn(0, 100)
            }
        }
    }

    /** 연결 직후 앱이 아는 상태를 기기에 맞춘다. */
    private fun pushInitialState() {
        scope.launch {
            thresholdsProvider?.invoke()?.let { sendThresholds(it) }
            if (forcePowerOnNextConnect) {
                forcePowerOnNextConnect = false
                sendPower(active = true)
            }
        }
    }

    // ── 송신 ──────────────────────────────────────────────────────

    suspend fun sendThresholds(thresholds: Thresholds): Boolean {
        val payload = ProtocolCodec.encodeThresholds(thresholds) ?: return false
        return writeRx(payload)
    }

    suspend fun sendPower(active: Boolean): Boolean = writeRx(ProtocolCodec.encodePower(active))

    private suspend fun writeRx(payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = rxChar ?: return false

        val deferred = CompletableDeferred<Boolean>()
        val op = GattOp("write RX", { performWrite(g, ch, payload) }, deferred)
        enqueue(op)

        val result = withTimeoutOrNull(BleConstants.GATT_OP_TIMEOUT_MS) { deferred.await() }
        if (result == null) {
            // 응답이 안 왔다. 큐가 막히지 않도록 이 작업만 버린다.
            Log.w(tag, "RX write 응답 없음 — 작업 폐기")
            abandonOp(op)
            return false
        }
        return result
    }

    private fun performWrite(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean {
        val writeType =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, payload, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = payload
                g.writeCharacteristic(ch)
            }
        }
    }

    // ── 블루투스 on/off 감시 ────────────────────────────────────────

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    clearOps()
                    closeGatt()
                    reconnectJob?.cancel()
                    _telemetry.value = null
                    _batteryPercent.value = null
                    _connectionState.value = ConnectionState.BluetoothOff
                }

                BluetoothAdapter.STATE_ON -> if (running) {
                    attempt = 0
                    beginScan()
                }
            }
        }
    }

    private fun registerAdapterReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterAdapterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(adapterReceiver) }
        receiverRegistered = false
    }
}
