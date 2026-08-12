package org.umbra.core.persistence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, 60_000L, pendingIntent
                    )
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, 60_000L, pendingIntent)
                }
                Log.d(TAG, "WatchdogAlarm: scheduled (exact)")
            } catch (e: SecurityException) {
                Log.w(TAG, "WatchdogAlarm: no exact alarm permission, using inexact")
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, 120_000L, pendingIntent)
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
        if (!PersistenceChain.isServiceRunning(context)) {
            Log.w(TAG, "WatchdogAlarm: service DEAD, restarting")
            PersistenceChain.start(context)
        }
        // Reschedule
        schedule(context)
    }
}
