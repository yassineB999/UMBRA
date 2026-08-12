package org.umbra.core.persistence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class WatchdogAlarm : BroadcastReceiver() {

    companion object {
        private const val TAG = "Umbra"
        private const val ALARM_REQUEST_CODE = 9001
        const val ACTION_WATCHDOG_CHECK = "org.umbra.core.WATCHDOG_CHECK"
        private const val INTERVAL_MS = 5 * 60 * 1000L

        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogAlarm::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)

            // CRITICAL: triggerAtMillis is an ABSOLUTE time on the ELAPSED_REALTIME
            // clock (time since boot), NOT a delay. Passing a raw 60_000L here made
            // the alarm fire continuously (60s-after-boot is in the past on a booted
            // device), producing a 5s broadcast loop and background FGS start storms.
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                        )
                        Log.d(TAG, "WatchdogAlarm: scheduled (exact)")
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                        )
                        Log.d(TAG, "WatchdogAlarm: scheduled (inexact, no exact-alarm permission)")
                    }
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                    Log.d(TAG, "WatchdogAlarm: scheduled (exact)")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "WatchdogAlarm: exact alarm blocked, using inexact")
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogAlarm::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)?.let {
                alarmManager.cancel(it)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Only attempt a restart when the service is actually gone. The foreground
        // service itself is resilient (START_STICKY + startForeground with a
        // graceful fallback), so this is purely a safety net.
        if (!PersistenceChain.isServiceRunning(context)) {
            Log.w(TAG, "WatchdogAlarm: service DEAD, restarting")
            PersistenceChain.start(context)
        }
        // Reschedule for the next tick.
        schedule(context)
    }
}
