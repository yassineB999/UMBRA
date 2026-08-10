package org.synapse.core.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager

object SandboxDetector {

    fun isRealDevice(context: Context): Boolean {
        if (isEmulator()) return false
        if (!hasSensors(context)) return false
        if (!hasTelephony(context)) return false
        if (!hasRealBattery(context)) return false
        return true
    }

    fun looksLikeAnalysis(): Boolean {
        val uptime = SystemClock.elapsedRealtime()
        if (uptime < 3600_000) return true  // less than 1 hour uptime — likely sandbox
        return false
    }

    private fun isEmulator(): Boolean {
        val model = Build.MODEL.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        val hardware = Build.HARDWARE.lowercase()

        val emulatorPatterns = listOf("google_sdk", "emulator", "android sdk", "sdk_gphone",
            "ranchu", "vbox86", "generic")

        if (emulatorPatterns.any { model.contains(it) || fingerprint.contains(it) || hardware.contains(it) })
            return true

        return Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
    }

    private fun hasSensors(context: Context): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getSensorList(Sensor.TYPE_ALL).size >= 5
    }

    private fun hasTelephony(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return !tm.networkOperatorName.isNullOrEmpty()
    }

    private fun hasRealBattery(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = (level * 100) / scale
        return pct in 1..100
    }
}
