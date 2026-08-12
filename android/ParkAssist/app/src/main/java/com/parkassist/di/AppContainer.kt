package com.parkassist.di

import android.content.Context
import com.parkassist.ble.MockBleRepository
import com.parkassist.ble.MultiZoneBleRepository
import com.parkassist.ble.SwitchableBleRepository
import com.parkassist.data.SettingsStore
import com.parkassist.domain.ProximityMonitor
import com.parkassist.protocol.Thresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 수동 DI 컨테이너.
 *
 * 화면 두 개짜리 앱에 DI 프레임워크를 얹을 이유가 없다. 싱글턴 보관과 배선만 한다.
 *
 * 여기서 만든 [bleRepository]를 화면과 Foreground Service가 **같이** 쓴다.
 * 그래야 화면을 닫아도 연결이 유지되고, 화면을 다시 열면 진행 중인 상태가 그대로 보인다.
 */
class AppContainer(context: Context) {

    /** 앱 수명 전체를 사는 스코프. Service/ViewModel보다 오래 산다. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(context)

    private val mockRepository = MockBleRepository(appScope)
    private val realRepository = MultiZoneBleRepository(context, appScope)

    val bleRepository = SwitchableBleRepository(realRepository, mockRepository, appScope)

    /** 화면과 Foreground Service가 같은 값을 보도록 여기서 한 번만 계산한다. */
    val proximityMonitor = ProximityMonitor(bleRepository, settingsStore, appScope)

    /** 연결 직후 각 기기에 밀어 넣을 최신 기준값. */
    @Volatile
    var thresholds: Thresholds = Thresholds.Default
        private set

    init {
        realRepository.thresholdsProvider = { thresholds }

        appScope.launch {
            // 주차 ↔ 주행 모드를 바꾸면 기준값 자체가 달라진다. 기기 부저가 쓰는 값이므로
            // 바뀌는 즉시 밀어 넣어야 한다. (아직 연결 전이면 연결될 때 다시 보낸다)
            settingsStore.activeThresholds.collectLatest { updated ->
                thresholds = updated
                bleRepository.sendThresholds(updated)
            }
        }
        appScope.launch {
            settingsStore.mockEnabled.collectLatest { bleRepository.setMockEnabled(it) }
        }
    }
}
