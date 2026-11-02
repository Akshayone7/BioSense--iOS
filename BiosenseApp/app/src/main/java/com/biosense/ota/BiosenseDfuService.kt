package com.biosense.ota

import androidx.compose.material3.ExperimentalMaterial3Api
import com.biosense.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

/**
 * Foreground service that runs the Nordic Secure DFU transfer. The Nordic
 * library does the heavy lifting; we only need this subclass so it can post a
 * "return to app" pending intent when the update finishes.
 *
 * Registered in AndroidManifest under ota/.
 */
@OptIn(ExperimentalMaterial3Api::class)
class BiosenseDfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out android.app.Activity> = MainActivity::class.java

    // isDebug=true forces verbose Nordic-lib logging into logcat. Cheap and
    // invaluable when a DFU fails in the field — leave on.
    override fun isDebug(): Boolean = true
}
