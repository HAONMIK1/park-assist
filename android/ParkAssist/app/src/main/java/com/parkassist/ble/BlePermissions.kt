package com.parkassist.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BLE 런타임 권한.
 *
 * Android 12(API 31)에서 블루투스 권한이 통째로 갈렸다.
 * - 12 이상: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`
 * - 11 이하: `ACCESS_FINE_LOCATION` (스캔이 위치 권한에 묶여 있었다)
 *
 * 매니페스트에서 `BLUETOOTH_SCAN`에 `neverForLocation`을 선언했으므로, 스캔 결과로
 * 위치를 추론하는 코드를 넣으면 안 된다.
 */
object BlePermissions {

    /** BLE 동작에 반드시 필요한 권한. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * 위 권한 + 알림 권한.
     *
     * 알림이 없으면 Foreground Service 고지가 안 보일 뿐 BLE는 동작하므로 [required]와
     * 분리해 둔다. 거부당해도 연결은 계속한다.
     */
    fun requiredWithNotification(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required() + Manifest.permission.POST_NOTIFICATIONS
        } else {
            required()
        }

    fun allGranted(context: Context): Boolean = required().all { granted(context, it) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
