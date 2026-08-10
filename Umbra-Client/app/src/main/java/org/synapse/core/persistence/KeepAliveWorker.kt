package org.synapse.core.persistence

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("Synapse", "KeepAliveWorker — launching service")
        val intent = Intent(applicationContext, SynapseService::class.java)
        applicationContext.startForegroundService(intent)
        return Result.success()
    }
}
