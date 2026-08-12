package org.umbra.core.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log

object SandboxDetector {

    private const val TAG = "Umbra.Sandbox"

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
        // On Android 14+, registerReceiver(null, ...) returns null (deprecated).
        // Use BatteryManager API instead.
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            pct in 1..100
        } catch (e: Exception) {
            // Fallback: assume real device if we can't determine
            Log.w(TAG, "Battery check failed: ${e.message}, assuming real device")
            true
        }
    }
}
