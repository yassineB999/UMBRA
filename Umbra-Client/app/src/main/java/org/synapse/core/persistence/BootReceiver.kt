package org.synapse.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    private val tag = "Synapse"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(tag, "BOOT_COMPLETED — scheduling restart")

        val request = OneTimeWorkRequestBuilder<KeepAliveWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
