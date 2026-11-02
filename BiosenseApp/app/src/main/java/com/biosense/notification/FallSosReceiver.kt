package com.biosense.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import com.biosense.data.MedicalIdStore

/**
 * Handles the "SOS" and "I'm OK" actions that hang off the fall-detection
 * notification. SOS opens the phone dialer pre-filled with the user's
 * emergency contact (we don't auto-dial — Android requires CALL_PHONE
 * runtime permission for that and users generally prefer confirming). I'm OK
 * just dismisses the alert.
 */
class FallSosReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) NotificationManagerCompat.from(ctx).cancel(notifId)

        when (intent.action) {
            ACTION_SOS -> launchDialer(ctx)
            ACTION_OK -> Unit
        }
    }

    private fun launchDialer(ctx: Context) {
        val phone = MedicalIdStore(ctx).load().emergencyPhone
        val dial = if (phone.isNotBlank()) Uri.parse("tel:$phone") else Uri.parse("tel:")
        val i = Intent(Intent.ACTION_DIAL, dial).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    companion object {
        const val ACTION_SOS = "com.biosense.action.FALL_SOS"
        const val ACTION_OK = "com.biosense.action.FALL_OK"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
