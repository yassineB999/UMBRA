package org.synapse.core.persistence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Triggers auto-start when the APK is installed or updated.
 *
 * Listens for:
 * - [Intent.ACTION_MY_PACKAGE_REPLACED] — fired to the app that was just updated
 * - [Intent.ACTION_PACKAGE_REPLACED] — fired when any package is updated (filtered for self)
 *
 * This ensures the agent starts immediately after `adb install -r` with no user
 * interaction whatsoever.
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Synapse"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "MY_PACKAGE_REPLACED — app updated, auto-starting")
                PersistenceChain.start(context)
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                // Filter for our own package only
                val updatedPkg = intent.data?.schemeSpecificPart
                if (updatedPkg == context.packageName) {
                    Log.d(TAG, "PACKAGE_REPLACED (self) — auto-starting")
                    PersistenceChain.start(context)
                }
            }
        }
    }
}
