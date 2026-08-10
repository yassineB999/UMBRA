package org.synapse.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.util.Log

/**
 * Network change trigger. When WiFi or mobile data connects, checks if the
 * SynapseService is alive and restarts it if dead.
 *
 * On Android 8.0+, CONNECTIVITY_ACTION is restricted for manifest-registered
 * receivers, but it will still fire when the app process is alive (kept alive
 * by the foreground service). This acts as an additional safety net.
 */
class NetworkReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Synapse"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

        val activeNetwork: NetworkInfo? = connectivityManager.activeNetworkInfo
        val isConnected = activeNetwork?.isConnectedOrConnecting == true

        if (!isConnected) {
            return // not interested in disconnects
        }

        Log.d(TAG, "NetworkReceiver — connectivity detected (${activeNetwork?.typeName})")

        if (!PersistenceChain.isServiceRunning(context)) {
            Log.w(TAG, "NetworkReceiver — service DEAD, restarting")
            PersistenceChain.start(context)
        } else {
            Log.d(TAG, "NetworkReceiver — service already alive")
        }
    }
}
