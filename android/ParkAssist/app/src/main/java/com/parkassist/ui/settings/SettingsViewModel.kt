package com.parkassist.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.parkassist.R
import com.parkassist.ble.SwitchableBleRepository
import com.parkassist.data.SettingsStore
import com.parkassist.di.AppContainer
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    /** 지금 편집 중인 모드. 거리 기준은 모드별로 따로 저장된다. */
    val mode: DriveMode = DriveMode.PARKING,
    /** 화면에 보이는 값(편집 중이면 편집본) */
    val thresholds: Thresholds = Thresholds.Default,
    val mockEnabled: Boolean = false,
    val soundEnabled: Boolean = true,
    val isDirty: Boolean = false,
    @StringRes val messageResId: Int? = null,
)

class SettingsViewModel(
    private val repository: SwitchableBleRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    /** 편집 중인 값. null이면 저장된 값을 그대로 보여준다. */
    private val draft = MutableStateFlow<Thresholds?>(null)
    private val message = MutableStateFlow<Int?>(null)

    private val modeAndThresholds =
        combine(settingsStore.driveMode, settingsStore.activeThresholds) { mode, saved ->
            mode to saved
        }

    val uiState: StateFlow<SettingsUiState> = combine(
        modeAndThresholds,
        settingsStore.mockEnabled,
        settingsStore.soundEnabled,
        draft,
        message,
    ) { (mode, saved), mockEnabled, soundEnabled, draftValue, messageResId ->
        SettingsUiState(
            mode = mode,
            thresholds = draftValue ?: saved,
            mockEnabled = mockEnabled,
            soundEnabled = soundEnabled,
            isDirty = draftValue != null && draftValue != saved,
            messageResId = messageResId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun updateThresholds(thresholds: Thresholds) {
        draft.value = thresholds.coerceOrdering()
    }

    /**
     * 저장 후 `0xBB` 전송.
     *
     * 전송 실패해도 로컬에는 저장한다. 기기에 저장된 값을 읽어올 방법이 없어서 앱의
     * 값이 진실이고, 다음에 연결될 때 다시 밀어 넣기 때문이다.
     */
    fun save() {
        val target = draft.value?.coerceOrdering() ?: return
        viewModelScope.launch {
            val mode = uiState.value.mode
            settingsStore.setThresholds(mode, target)
            val sent = repository.sendThresholds(target)
            draft.value = null
            message.value = if (sent) R.string.settings_saved else R.string.settings_saved_offline
        }
    }

    fun setMockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMockEnabled(enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSoundEnabled(enabled) }
    }

    /** 목업 모드에서만 동작한다. 재연결 UI 확인용. */
    fun simulateDisconnect() {
        repository.mockControls?.simulateDisconnect()
    }

    fun messageShown() {
        message.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(container.bleRepository, container.settingsStore)
            }
        }
    }
}
