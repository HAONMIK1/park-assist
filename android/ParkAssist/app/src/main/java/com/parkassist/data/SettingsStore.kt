package com.parkassist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.parkassist.protocol.DriveMode
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "park_assist_settings")

/**
 * 앱 설정 저장소.
 *
 * 기기에 저장된 거리 기준을 읽어올 방법이 없으므로(docs/ble-protocol.md 7절)
 * **여기 저장된 값이 진실**이고, 연결될 때마다 기기로 밀어 넣는다.
 *
 * 거리 기준은 **모드별로 따로** 보관한다. 주차용 기준(30/60/120)을 주행 중에 쓰면
 * 뒤차가 붙어 있는 정상 상황에서 기기 부저가 쉬지 않고 울린다.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    private val preferences: Flow<Preferences> = store.data
        .catch { cause ->
            // 디스크 오류로 화면 전체가 죽는 것보다 기본값으로 뜨는 편이 낫다.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }

    val driveMode: Flow<DriveMode> = preferences.map { prefs ->
        prefs[KEY_DRIVE_MODE]
            ?.let { name -> DriveMode.entries.firstOrNull { it.name == name } }
            ?: DriveMode.PARKING
    }

    /** 지금 모드에 해당하는 거리 기준. 기기에 밀어 넣는 값이기도 하다. */
    val activeThresholds: Flow<Thresholds> = combine(preferences, driveMode) { prefs, mode ->
        prefs.thresholdsFor(mode)
    }

    val mockEnabled: Flow<Boolean> = preferences.map { it[KEY_MOCK] ?: false }

    /** 앱 경고음. 기기 부저와 별개다(ADR 0003). */
    val soundEnabled: Flow<Boolean> = preferences.map { it[KEY_SOUND] ?: true }

    fun thresholds(mode: DriveMode): Flow<Thresholds> = preferences.map { it.thresholdsFor(mode) }

    suspend fun setDriveMode(mode: DriveMode) {
        store.edit { it[KEY_DRIVE_MODE] = mode.name }
    }

    suspend fun setThresholds(mode: DriveMode, thresholds: Thresholds) {
        val valid = thresholds.coerceOrdering()
        store.edit { prefs ->
            prefs[keyNear(mode)] = valid.nearCm
            prefs[keyMid(mode)] = valid.midCm
            prefs[keyFar(mode)] = valid.farCm
        }
    }

    suspend fun setMockEnabled(enabled: Boolean) {
        store.edit { it[KEY_MOCK] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        store.edit { it[KEY_SOUND] = enabled }
    }

    private fun Preferences.thresholdsFor(mode: DriveMode): Thresholds {
        val defaults = mode.defaultThresholds
        return Thresholds(
            nearCm = this[keyNear(mode)] ?: defaults.nearCm,
            midCm = this[keyMid(mode)] ?: defaults.midCm,
            farCm = this[keyFar(mode)] ?: defaults.farCm,
        ).coerceOrdering()
    }

    private companion object {
        val KEY_MOCK = booleanPreferencesKey("mock_enabled")
        val KEY_SOUND = booleanPreferencesKey("sound_enabled")
        val KEY_DRIVE_MODE = stringPreferencesKey("drive_mode")

        fun keyNear(mode: DriveMode) = intPreferencesKey("threshold_near_cm_${mode.name}")
        fun keyMid(mode: DriveMode) = intPreferencesKey("threshold_mid_cm_${mode.name}")
        fun keyFar(mode: DriveMode) = intPreferencesKey("threshold_far_cm_${mode.name}")
    }
}
