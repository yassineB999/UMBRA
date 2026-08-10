package org.synapse.core.persistence

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryPrompt {

    fun request(activity: Activity) {
        val pm = activity.getSystemService(Activity.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        } else {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
            )
        }
    }
}
