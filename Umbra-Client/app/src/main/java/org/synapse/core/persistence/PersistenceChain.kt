package org.synapse.core.persistence

import android.content.Context
import android.content.Intent
import android.util.Log

object PersistenceChain {
    private const val TAG = "Synapse"

    fun start(context: Context) {
        Log.d(TAG, "Persistence chain starting")
        val intent = Intent(context, SynapseService::class.java)
        context.startForegroundService(intent)
    }
}
