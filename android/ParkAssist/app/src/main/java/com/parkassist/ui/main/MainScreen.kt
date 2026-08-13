package com.parkassist.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.parkassist.R
import com.parkassist.ble.ConnectionState
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.ProximityLevel
import com.parkassist.protocol.SensorZone
import com.parkassist.ui.theme.DistanceDisplay
import com.parkassist.ui.theme.DistanceDisplayText
import com.parkassist.ui.theme.LevelCaution
import com.parkassist.ui.theme.LevelDanger
import com.parkassist.ui.theme.LevelNone
import com.parkassist.ui.theme.LevelWarn
import com.parkassist.ui.theme.OnSurfaceMuted
import com.parkassist.ui.theme.color

@Composable
fun MainScreen(
    state: MainUiState,
    onTogglePower: () -> Unit,
    onModeChange: (DriveMode) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModeSelector(mode = state.mode, onModeChange = onModeChange)

        Spacer(Modifier.height(12.dp))

        StatusBar(state)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            CarRadar(
                rear = state.channels(SensorZone.REAR),
                front = state.channels(SensorZone.FRONT),
                // aspectRatio(1f) + fillMaxWidth 로 잡으면 세로가 짧은 기기에서 정사각형이
                // 남은 공간을 넘어 아래 요소를 덮는다. CarRadar 가 min(width, height) 로
                // 반지름을 잡으므로 fillMaxSize 만으로 항상 원이 유지된다.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            )
        }

        Readout(state)

        Spacer(Modifier.height(16.dp))

        BottomControls(
            powerActive = state.powerActive,
            enabled = state.anyConnected,
            onTogglePower = onTogglePower,
            onOpenSettings = onOpenSettings,
        )
    }
}

// ── 모드 선택 ──────────────────────────────────────────────────────

/**
 * 주차 / 주행 전환.
 *
 * 두 모드는 경보 규칙이 완전히 달라서 자주 바꾸게 된다. 설정 안에 숨기지 않고
 * 첫 화면에 크게 둔다.
 */
@Composable
private fun ModeSelector(mode: DriveMode, onModeChange: (DriveMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        DriveMode.entries.forEach { entry ->
            val selected = entry == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onModeChange(entry) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (entry) {
                            DriveMode.PARKING -> R.string.mode_parking
                            DriveMode.DRIVING -> R.string.mode_driving
                        }
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else OnSurfaceMuted,
                )
            }
        }
    }
}

// ── 상단: 존별 연결 상태 + 배터리 ───────────────────────────────────

@Composable
private fun StatusBar(state: MainUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row {
            ZoneChip(SensorZone.FRONT, state.connection(SensorZone.FRONT))
            Spacer(Modifier.width(10.dp))
            ZoneChip(SensorZone.REAR, state.connection(SensorZone.REAR))
        }
        BatteryIndicator(state.batteryPercent)
    }
}

/** 기기가 두 대라 존마다 따로 표시한다. 한쪽만 끊겨도 바로 보여야 한다. */
@Composable
private fun ZoneChip(zone: SensorZone, connection: ConnectionState) {
    val dotColor = when (connection) {
        ConnectionState.Connected -> LevelCaution
        ConnectionState.Scanning, ConnectionState.Connecting -> LevelWarn
        is ConnectionState.Reconnecting -> LevelWarn
        ConnectionState.Idle -> LevelNone
        else -> LevelDanger
    }

    val label = stringResource(
        when (zone) {
            SensorZone.FRONT -> R.string.zone_front
            SensorZone.REAR -> R.string.zone_rear
        }
    )

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "$label ${connection.describe()}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(9.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun ConnectionState.describe(): String = when (this) {
    ConnectionState.Connected -> "연결됨"
    ConnectionState.Scanning -> "검색 중"
    ConnectionState.Connecting -> "연결 중"
    ConnectionState.BluetoothOff -> "블루투스 꺼짐"
    ConnectionState.PermissionRequired -> "권한 필요"
    ConnectionState.Idle -> "대기"
    is ConnectionState.Reconnecting -> "재연결 중"
    is ConnectionState.Disconnected -> "연결 끊김"
}

@Composable
private fun BatteryIndicator(percent: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.BatteryFull,
            contentDescription = null,
            tint = if (percent != null && percent <= 20) LevelDanger else OnSurfaceMuted,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            // 두 기기 중 낮은 쪽. 기기가 Battery Service를 제공하지 않으면 "--".
            text = percent?.let { stringResource(R.string.battery_percent, it) }
                ?: stringResource(R.string.battery_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
    }
}

// ── 가운데 아래: 거리 / 경보 문구 ───────────────────────────────────

@Composable
private fun Readout(state: MainUiState) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (state.mode) {
            DriveMode.PARKING -> ParkingReadout(state)
            DriveMode.DRIVING -> DrivingReadout(state)
        }
    }
}

@Composable
private fun ParkingReadout(state: MainUiState) {
    val color by animateColorAsState(
        targetValue = when {
            !state.anyConnected -> LevelNone
            state.anyBlindZone -> ProximityLevel.CRITICAL.color()
            state.isStale -> LevelNone
            else -> state.worst.color()
        },
        label = "readoutColor",
    )

    when {
        !state.anyConnected ->
            Text(stringResource(R.string.value_unknown), style = DistanceDisplay, color = LevelNone)

        // 사각지대에서는 숫자가 없다. 그렇다고 비워두면 가장 위험한 순간에 화면이
        // 텅 비므로 문구로 대신한다.
        state.anyBlindZone -> ZoneLabelled(state.alertZone) {
            Text(
                text = stringResource(R.string.alarm_very_close),
                style = DistanceDisplayText,
                color = color,
                textAlign = TextAlign.Center,
            )
        }

        state.isStale -> Text(
            text = stringResource(R.string.status_no_signal),
            style = DistanceDisplayText,
            color = LevelNone,
            textAlign = TextAlign.Center,
        )

        state.nearestCm != null -> ZoneLabelled(state.alertZone) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.semantics {
                    contentDescription = "가장 가까운 거리 ${state.nearestCm} 센티미터"
                },
            ) {
                Text(text = "${state.nearestCm}", style = DistanceDisplay, color = color)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.unit_cm),
                    style = MaterialTheme.typography.headlineLarge,
                    color = color,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }

        else -> Text(stringResource(R.string.value_unknown), style = DistanceDisplay, color = LevelNone)
    }
}

@Composable
private fun DrivingReadout(state: MainUiState) {
    when {
        !state.anyConnected -> Text(
            text = stringResource(R.string.status_disconnected),
            style = DistanceDisplayText,
            color = LevelNone,
        )

        state.urgent && state.alertZone != null -> Text(
            text = stringResource(R.string.alarm_approach, zoneName(state.alertZone)),
            style = DistanceDisplayText,
            color = LevelDanger,
            textAlign = TextAlign.Center,
        )

        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.driving_watching),
                style = MaterialTheme.typography.headlineLarge,
                color = OnSurfaceMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.driving_watching_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 숫자 위에 어느 쪽인지 작게 붙인다. 앞뒤 센서가 따로 있으므로 방향이 중요하다. */
@Composable
private fun ZoneLabelled(zone: SensorZone?, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (zone != null) {
            Text(
                text = zoneName(zone),
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfaceMuted,
            )
        }
        content()
    }
}

@Composable
private fun zoneName(zone: SensorZone): String = stringResource(
    when (zone) {
        SensorZone.FRONT -> R.string.zone_front
        SensorZone.REAR -> R.string.zone_rear
    }
)

// ── 하단: 전원 버튼 + 설정 ─────────────────────────────────────────

@Composable
private fun BottomControls(
    powerActive: Boolean,
    enabled: Boolean,
    onTogglePower: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onTogglePower,
            enabled = enabled,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (powerActive) LevelDanger else LevelCaution,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .weight(1f)
                .height(88.dp),
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(if (powerActive) R.string.power_off else R.string.power_on),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        Spacer(Modifier.width(14.dp))

        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}
