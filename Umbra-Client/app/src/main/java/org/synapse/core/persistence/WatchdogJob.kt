package org.umbra.core.persistence

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * JobScheduler-based secondary watchdog. Fires every 15 minutes.
 * Always runs — no battery/idle constraints.
 * Checks if the UmbraService is alive; restarts it if dead.
 */
class WatchdogJob : JobService() {

    companion object {
        private const val TAG = "Umbra"
        private const val JOB_ID = 9002

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            // Cancel any existing before rescheduling
            scheduler.cancel(JOB_ID)

            val component = ComponentName(context, WatchdogJob::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(15 * 60 * 1000L) // 15 minutes
                .setRequiresBatteryNotLow(false)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .setPersisted(true) // survive reboots
                .build()

            val result = scheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.d(TAG, "WatchdogJob scheduled — every 15 min")
            } else {
                Log.w(TAG, "WatchdogJob schedule FAILED")
            }
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "WatchdogJob — tick")

        if (!PersistenceChain.isServiceRunning(this)) {
            Log.w(TAG, "WatchdogJob — service DEAD, restarting")
            PersistenceChain.start(this)
        } else {
            Log.d(TAG, "WatchdogJob — service alive")
        }

        params?.let { jobFinished(it, false) }
        return false // no ongoing work
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "WatchdogJob — stopped")
        params?.let { jobFinished(it, true) }
        return true // reschedule
    }
}
