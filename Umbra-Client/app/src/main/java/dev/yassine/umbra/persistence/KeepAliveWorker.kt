package dev.yassine.umbra.persistence

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
        Log.d("Umbra", "KeepAliveWorker — launching service")
        val intent = Intent(applicationContext, UmbraService::class.java)
        applicationContext.startForegroundService(intent)
        return Result.success()
    }
}
