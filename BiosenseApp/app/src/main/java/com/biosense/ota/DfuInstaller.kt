package com.biosense.ota

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter
import no.nordicsemi.android.dfu.DfuServiceInitiator
import no.nordicsemi.android.dfu.DfuServiceListenerHelper

/**
 * Kicks off a Nordic Secure DFU against the currently-paired wearable using a
 * .zip package the user picked from the device's storage (produced by
 * adafruit-nrfutil for XIAO nRF52840). The returned Flow emits progress /
 * status until the transfer aborts or completes.
 */
object DfuInstaller {

    sealed interface Event {
        data object Connecting : Event
        data object Starting : Event
        data object Validating : Event
        data class Progress(val percent: Int, val speedBps: Float, val avgSpeedBps: Float) : Event
        data class Completed(val address: String) : Event
        data class Aborted(val address: String) : Event
        data class Error(val error: Int, val errorType: Int, val message: String?) : Event
    }

    fun install(ctx: Context, deviceMac: String, packageUri: Uri): Flow<Event> = callbackFlow {
        val listener = object : DfuProgressListenerAdapter() {
            override fun onDeviceConnecting(deviceAddress: String) { trySend(Event.Connecting) }
            override fun onDfuProcessStarting(deviceAddress: String) { trySend(Event.Starting) }
            override fun onFirmwareValidating(deviceAddress: String) { trySend(Event.Validating) }
            override fun onProgressChanged(
                deviceAddress: String,
                percent: Int,
                speed: Float,
                avgSpeed: Float,
                currentPart: Int,
                partsTotal: Int,
            ) { trySend(Event.Progress(percent, speed, avgSpeed)) }
            override fun onDfuCompleted(deviceAddress: String) {
                trySend(Event.Completed(deviceAddress)); close()
            }
            override fun onDfuAborted(deviceAddress: String) {
                trySend(Event.Aborted(deviceAddress)); close()
            }
            override fun onError(deviceAddress: String, error: Int, errorType: Int, message: String?) {
                trySend(Event.Error(error, errorType, message)); close()
            }
        }
        DfuServiceListenerHelper.registerProgressListener(ctx, listener)

        DfuServiceInitiator(deviceMac)
            .setKeepBond(true)
            .setPrepareDataObjectDelay(300)
            .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
            .setZip(packageUri)
            .start(ctx, BiosenseDfuService::class.java)

        awaitClose { DfuServiceListenerHelper.unregisterProgressListener(ctx, listener) }
    }
}
