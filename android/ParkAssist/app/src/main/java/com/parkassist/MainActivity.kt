package com.parkassist

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parkassist.ble.BlePermissions
import com.parkassist.di.AppContainer
import com.parkassist.service.ParkAssistService
import com.parkassist.ui.main.MainScreen
import com.parkassist.ui.main.MainViewModel
import com.parkassist.ui.settings.SettingsScreen
import com.parkassist.ui.settings.SettingsViewModel
import com.parkassist.ui.theme.ParkAssistTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 주차하는 동안 화면이 꺼지면 곤란하다. 화면이 꺼져도 연결과 소리는
        // Foreground Service가 이어가지만, 보고 있을 때는 켜 둔다.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ParkAssistTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ParkAssistRoot(appContainer)
                }
            }
        }
    }
}

private enum class Screen { Main, Settings }

@Composable
private fun ParkAssistRoot(container: AppContainer) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(BlePermissions.allGranted(context)) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 알림 권한은 거부돼도 상관없다. BLE 권한만 확인한다.
        permissionsGranted = BlePermissions.allGranted(context)
    }

    LaunchedEffect(permissionsGranted, permissionRequested) {
        if (permissionsGranted) {
            ParkAssistService.start(context)
        } else if (!permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(BlePermissions.requiredWithNotification())
        }
    }

    if (!permissionsGranted) {
        PermissionRequiredScreen(
            onRequest = { permissionLauncher.launch(BlePermissions.requiredWithNotification()) }
        )
        return
    }

    var screen by rememberSaveable { mutableStateOf(Screen.Main) }

    when (screen) {
        Screen.Main -> {
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MainScreen(
                state = state,
                onTogglePower = viewModel::togglePower,
                onModeChange = viewModel::setMode,
                onOpenSettings = { screen = Screen.Settings },
            )
        }

        Screen.Settings -> {
            BackHandler { screen = Screen.Main }
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            SettingsScreen(
                state = state,
                onThresholdsChange = viewModel::updateThresholds,
                onSave = viewModel::save,
                onSoundEnabledChange = viewModel::setSoundEnabled,
                onMockEnabledChange = viewModel::setMockEnabled,
                onSimulateDisconnect = viewModel::simulateDisconnect,
                onMessageShown = viewModel::messageShown,
                onBack = { screen = Screen.Main },
            )
        }
    }
}

@Composable
private fun PermissionRequiredScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp, bottom = 40.dp),
        )
        Button(
            onClick = onRequest,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        ) {
            Text(
                text = stringResource(R.string.permission_grant),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}
