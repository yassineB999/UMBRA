package org.umbra.core.modules

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NotificationListenerService that captures all device notifications.
 * Feeds into NotificationModule buffer.
 *
 * MUST be manually enabled by user in:
 * Settings → Sound & notification → Notification access → Umbra
 */
class UmbraNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Umbra.NotifListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        try {
            NotificationModule.onNotificationPosted(sbn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // Optionally track removals
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }
}
