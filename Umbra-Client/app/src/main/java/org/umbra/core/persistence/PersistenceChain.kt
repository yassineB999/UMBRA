package org.umbra.core.persistence

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object PersistenceChain {
    private const val TAG = "Umbra"

    /**
     * Start the Umbra foreground service and schedule all watchdog layers.
     * Called from: ContentProvider, BootReceiver, Alarm, Job, Install, Network, Power.
     */
    fun start(context: Context) {
        Log.d(TAG, "Persistence chain starting")
        try {
            val intent = Intent(context, UmbraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    // Background FGS start blocked on Android 14+ from receiver context.
                    // Fall back to plain startService — the service will still run,
                    // it just can't promote itself to foreground without a notification.
                    Log.w(TAG, "startForegroundService blocked, falling back to startService: ${e.message}")
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UmbraService", e)
        }

        // Schedule all watchdog layers on every start call to ensure they
        // are always armed — even if the app was force-stopped and lost them.
        scheduleWatchdogs(context)
    }

    /**
     * Check if the UmbraService is currently running.
     */
    fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val serviceClassName = UmbraService::class.java.name
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClassName == service.service.className) {
                return true
            }
        }
        return false
    }

    /**
     * Arm all watchdog layers: Alarm, JobScheduler.
     */
    private fun scheduleWatchdogs(context: Context) {
        try {
            WatchdogAlarm.schedule(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule WatchdogAlarm", e)
        }

        try {
            WatchdogJob.schedule(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule WatchdogJob", e)
        }
    }
}
