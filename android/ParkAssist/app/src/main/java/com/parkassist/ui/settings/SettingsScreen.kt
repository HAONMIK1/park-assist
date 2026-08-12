package com.parkassist.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.parkassist.R
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.Thresholds
import com.parkassist.ui.theme.LevelCaution
import com.parkassist.ui.theme.LevelDanger
import com.parkassist.ui.theme.LevelWarn
import com.parkassist.ui.theme.OnSurfaceMuted
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThresholdsChange: (Thresholds) -> Unit,
    onSave: () -> Unit,
    onSoundEnabledChange: (Boolean) -> Unit,
    onMockEnabledChange: (Boolean) -> Unit,
    onSimulateDisconnect: () -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.messageResId?.let { stringResource(it) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(64.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            SectionTitle(
                stringResource(
                    R.string.settings_thresholds_title,
                    stringResource(
                        when (state.mode) {
                            DriveMode.PARKING -> R.string.mode_parking
                            DriveMode.DRIVING -> R.string.mode_driving
                        }
                    ),
                )
            )
            Text(
                // 기준은 모드별로 따로 저장된다. 지금 어느 쪽을 고치는지 분명히 보여준다.
                text = stringResource(
                    when (state.mode) {
                        DriveMode.PARKING -> R.string.settings_thresholds_description_parking
                        DriveMode.DRIVING -> R.string.settings_thresholds_description_driving
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
            Spacer(Modifier.height(20.dp))

            ThresholdSlider(
                label = stringResource(R.string.settings_threshold_danger),
                valueCm = state.thresholds.nearCm,
                range = Thresholds.NEAR_RANGE,
                accent = LevelDanger,
                onValueChange = { onThresholdsChange(state.thresholds.copy(nearCm = it)) },
            )
            ThresholdSlider(
                label = stringResource(R.string.settings_threshold_warn),
                valueCm = state.thresholds.midCm,
                range = Thresholds.MID_RANGE,
                accent = LevelWarn,
                onValueChange = { onThresholdsChange(state.thresholds.copy(midCm = it)) },
            )
            ThresholdSlider(
                label = stringResource(R.string.settings_threshold_caution),
                valueCm = state.thresholds.farCm,
                range = Thresholds.FAR_RANGE,
                accent = LevelCaution,
                onValueChange = { onThresholdsChange(state.thresholds.copy(farCm = it)) },
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSave,
                enabled = state.isDirty,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
            ) {
                Text(
                    stringResource(R.string.settings_save),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }

            SectionDivider()

            ToggleRow(
                title = stringResource(R.string.settings_sound_title),
                description = stringResource(R.string.settings_sound_description),
                checked = state.soundEnabled,
                onCheckedChange = onSoundEnabledChange,
            )

            SectionDivider()

            ToggleRow(
                title = stringResource(R.string.settings_mock_title),
                description = stringResource(R.string.settings_mock_description),
                checked = state.mockEnabled,
                onCheckedChange = onMockEnabledChange,
            )

            if (state.mockEnabled) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onSimulateDisconnect,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_mock_disconnect),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(28.dp))
}

/**
 * 슬라이더 + 큼직한 −/+ 버튼.
 *
 * 60대 사용자에게 슬라이더만 주면 1cm 단위 조정이 어렵다. 버튼으로도 조정할 수 있게 한다.
 */
@Composable
private fun ThresholdSlider(
    label: String,
    valueCm: Int,
    range: IntRange,
    accent: Color,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = accent)
            Text(
                text = stringResource(R.string.value_cm, valueCm),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(
                icon = Icons.Default.Remove,
                description = stringResource(R.string.decrease),
                onClick = { onValueChange((valueCm - 1).coerceIn(range.first, range.last)) },
            )
            Slider(
                value = valueCm.toFloat(),
                onValueChange = { onValueChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            StepButton(
                icon = Icons.Default.Add,
                description = stringResource(R.string.increase),
                onClick = { onValueChange((valueCm + 1).coerceIn(range.first, range.last)) },
            )
        }
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(60.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onCheckedChange(!checked) }
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
