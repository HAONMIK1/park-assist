package com.parkassist.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.parkassist.MainActivity
import com.parkassist.R
import com.parkassist.appContainer
import com.parkassist.audio.AlarmPlayer
import com.parkassist.ble.ConnectionState
import com.parkassist.ble.summaryConnection
import com.parkassist.di.AppContainer
import com.parkassist.domain.MonitorState
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.SensorZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 화면이 꺼져도 BLE 연결과 경고음을 유지하는 Foreground Service.
 *
 * 연결의 소유자는 이 서비스다. 화면(ViewModel)은 상태를 읽고 명령을 보낼 뿐이며,
 * 액티비티가 사라져도 연결은 그대로 남는다.
 *
 * **앱 경고음도 여기서 낸다.** 티맵을 보며 운전하는 중이거나 화면이 꺼진 상태에서도
 * 울려야 하기 때문이다. (다시 말하지만 주 경보는 기기 부저다 — ADR 0001)
 */
class ParkAssistService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var container: AppContainer
    private lateinit var alarmPlayer: AlarmPlayer

    override fun onCreate() {
        super.onCreate()
        container = appContainer
        alarmPlayer = AlarmPlayer(this, scope)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        container.bleRepository.start()
        observe()
        return START_STICKY
    }

    override fun onDestroy() {
        alarmPlayer.release()
        container.bleRepository.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 상태 관찰 ──────────────────────────────────────────────────

    private fun observe() {
        // 소리: 사용자가 껐거나 기기가 대기 모드면 울리지 않는다.
        scope.launch {
            combine(
                container.proximityMonitor.state,
                container.settingsStore.soundEnabled,
                container.bleRepository.powerActive,
            ) { monitor, soundEnabled, powerActive ->
                if (soundEnabled && powerActive) monitor else null
            }.collect { monitor ->
                alarmPlayer.setTone(monitor?.tone, urgent = monitor?.urgent == true)
            }
        }

        // 알림: 내용이 바뀔 때만 갱신한다. 10Hz로 알림을 갈아치우면 안 된다.
        scope.launch {
            combine(
                container.proximityMonitor.state,
                container.bleRepository.zones,
            ) { monitor, zones -> notificationText(monitor, zones.summaryConnection) }
                .distinctUntilChanged()
                .collect { text ->
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(text))
                }
        }
    }

    private fun notificationText(monitor: MonitorState, connection: ConnectionState): String {
        if (connection != ConnectionState.Connected) return connectionLabel(connection)

        return when (monitor.mode) {
            DriveMode.DRIVING -> when (val zone = monitor.alertZone) {
                null -> getString(R.string.notification_driving_watching)
                else -> getString(R.string.notification_driving_alert, zoneLabel(zone))
            }

            DriveMode.PARKING -> {
                val zone = monitor.alertZone
                val proximity = zone?.let { monitor.zone(it).proximity }
                when {
                    proximity?.anyBlindZone == true ->
                        getString(R.string.notification_blind, zoneLabel(zone))

                    proximity?.nearestCm != null ->
                        getString(R.string.notification_distance, zoneLabel(zone), proximity.nearestCm)

                    else -> getString(R.string.status_connected)
                }
            }
        }
    }

    private fun connectionLabel(state: ConnectionState): String = when (state) {
        ConnectionState.Connected -> getString(R.string.status_connected)
        ConnectionState.Scanning -> getString(R.string.status_scanning)
        ConnectionState.Connecting -> getString(R.string.status_connecting)
        ConnectionState.BluetoothOff -> getString(R.string.status_bluetooth_off)
        ConnectionState.PermissionRequired -> getString(R.string.status_permission_required)
        ConnectionState.Idle -> getString(R.string.status_idle)
        is ConnectionState.Reconnecting -> getString(R.string.status_reconnecting, state.attempt)
        is ConnectionState.Disconnected -> getString(R.string.status_disconnected)
    }

    private fun zoneLabel(zone: SensorZone): String = getString(
        when (zone) {
            SensorZone.FRONT -> R.string.zone_front
            SensorZone.REAR -> R.string.zone_rear
        }
    )

    // ── 알림 ──────────────────────────────────────────────────────

    private fun startInForeground() {
        val notification = buildNotification(getString(R.string.status_scanning))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            0,
            getString(R.string.notification_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, ParkAssistService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // 소리는 AlarmPlayer가 낸다. 알림 자체는 조용해야 한다.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "park_assist_connection"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.parkassist.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ParkAssistService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ParkAssistService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
