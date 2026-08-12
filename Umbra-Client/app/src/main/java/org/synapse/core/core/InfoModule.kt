package org.umbra.core.core

import android.content.Context
import android.os.Build
import android.provider.Settings

object InfoModule {

    fun gather(context: Context): UmbraResponse.DeviceInfoResponse {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )

        return UmbraResponse.DeviceInfoResponse(
            model = Build.MODEL,
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            sdk = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE,
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            android_id = androidId ?: "unknown",
            imei = ""
        )
    }
}
