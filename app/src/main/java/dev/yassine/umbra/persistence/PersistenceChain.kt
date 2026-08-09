package dev.yassine.umbra.persistence

import android.content.Context
import android.content.Intent
import android.util.Log

object PersistenceChain {
    private const val TAG = "Umbra"

    fun start(context: Context) {
        Log.d(TAG, "Persistence chain starting")
        val intent = Intent(context, UmbraService::class.java)
        context.startForegroundService(intent)
    }
}
