package dev.yassine.umbra.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DeviceInfo(
    val model: String = Build.MODEL,
    val brand: String = Build.BRAND,
    val manufacturer: String = Build.MANUFACTURER,
    val sdk: Int = Build.VERSION.SDK_INT,
    val release: String = Build.VERSION.RELEASE,
    val fingerprint: String = Build.FINGERPRINT,
    val hardware: String = Build.HARDWARE,
    val androidId: String = "",
    val imei: String = ""
)

object InfoModule {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun gather(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )

        val info = DeviceInfo(androidId = androidId ?: "unknown")

        return try {
            json.encodeToString(info)
        } catch (_: Exception) {
            """{"model":"${Build.MODEL}","sdk":${Build.VERSION.SDK_INT}}"""
        }
    }
}
