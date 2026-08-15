package org.umbra.core.persistence

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DeviceAdminReceiver — enables the app to be registered as a device admin
 * via `adb shell dpm set-active-admin org.umbra.core/.persistence.UmbraAdminReceiver`.
 *
 * Once active admin, the app can use DevicePolicyManager.setPermissionGrantState()
 * to silently grant runtime permissions to itself (requires device owner or
 * profile owner for cross-app grants, but a regular admin can set
 * setPermissionPolicy(PERMISSION_GRANT_STATE_GRANTED) for its own package
 * on some OEM implementations).
 *
 * This receiver must be declared in AndroidManifest.xml with
 * android:permission="android.permission.BIND_DEVICE_ADMIN".
 */
class UmbraAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "Umbra"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device admin disabled")
    }
}
