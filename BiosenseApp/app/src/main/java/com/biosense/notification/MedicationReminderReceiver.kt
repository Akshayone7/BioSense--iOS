package com.biosense.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.biosense.MainActivity
import com.biosense.R
import com.biosense.data.MedicationStore

/**
 * Fires when a scheduled dose is due. Shows a high-priority notification with
 * "Taken / Skipped / Snooze 15 min" actions and reschedules the *next* dose
 * (24h out or the next HH:MM tomorrow) so the reminder chain keeps running.
 */
@OptIn(ExperimentalMaterial3Api::class)
class MedicationReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TAKEN, ACTION_SKIPPED, ACTION_SNOOZE ->
                handleUserAction(ctx, intent)
            else -> showReminder(ctx, intent)
        }
    }

    private fun showReminder(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(MedicationScheduler.EXTRA_MED_ID) ?: return
        val name = intent.getStringExtra(MedicationScheduler.EXTRA_MED_NAME).orEmpty()
        val dose = intent.getStringExtra(MedicationScheduler.EXTRA_MED_DOSE).orEmpty()
        val hhmm = intent.getStringExtra(MedicationScheduler.EXTRA_SCHEDULED_HHMM).orEmpty()

        ensureChannel(ctx)
        val notifId = ("$id|$hhmm").hashCode() and 0x7FFFFFFF

        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("$name · $dose")
            .setContentText("Scheduled dose at $hhmm — tap to log it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "Taken", actionPending(ctx, notifId, id, name, dose, hhmm, ACTION_TAKEN))
            .addAction(0, "Skip", actionPending(ctx, notifId, id, name, dose, hhmm, ACTION_SKIPPED))
            .addAction(0, "Snooze 15m", actionPending(ctx, notifId, id, name, dose, hhmm, ACTION_SNOOZE))
            .build()
        NotificationManagerCompat.from(ctx).notify(notifId, n)

        // Reschedule the *next* occurrence — same HH:MM tomorrow. AlarmManager
        // exact alarms don't repeat, so we chain here.
        MedicationScheduler.rescheduleAll(ctx)
    }

    private fun handleUserAction(ctx: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val id = intent.getStringExtra(MedicationScheduler.EXTRA_MED_ID) ?: return
        val name = intent.getStringExtra(MedicationScheduler.EXTRA_MED_NAME).orEmpty()
        val dose = intent.getStringExtra(MedicationScheduler.EXTRA_MED_DOSE).orEmpty()
        val hhmm = intent.getStringExtra(MedicationScheduler.EXTRA_SCHEDULED_HHMM).orEmpty()

        when (intent.action) {
            ACTION_TAKEN -> {
                MedicationStore.of(ctx).logEvent(
                    MedicationStore.Event(
                        System.currentTimeMillis(), id, name, dose,
                        MedicationStore.Action.TAKEN, hhmm,
                    )
                )
                if (notifId != -1) NotificationManagerCompat.from(ctx).cancel(notifId)
            }
            ACTION_SKIPPED -> {
                MedicationStore.of(ctx).logEvent(
                    MedicationStore.Event(
                        System.currentTimeMillis(), id, name, dose,
                        MedicationStore.Action.SKIPPED, hhmm,
                    )
                )
                if (notifId != -1) NotificationManagerCompat.from(ctx).cancel(notifId)
            }
            ACTION_SNOOZE -> {
                MedicationStore.of(ctx).logEvent(
                    MedicationStore.Event(
                        System.currentTimeMillis(), id, name, dose,
                        MedicationStore.Action.SNOOZED, hhmm,
                    )
                )
                if (notifId != -1) NotificationManagerCompat.from(ctx).cancel(notifId)
                // Re-post the same reminder in 15 minutes via an ad-hoc alarm.
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val trigger = System.currentTimeMillis() + 15L * 60L * 1000L
                val pi = PendingIntent.getBroadcast(
                    ctx,
                    (id + hhmm + "snooze").hashCode(),
                    Intent(ctx, MedicationReminderReceiver::class.java)
                        .putExtra(MedicationScheduler.EXTRA_MED_ID, id)
                        .putExtra(MedicationScheduler.EXTRA_MED_NAME, name)
                        .putExtra(MedicationScheduler.EXTRA_MED_DOSE, dose)
                        .putExtra(MedicationScheduler.EXTRA_SCHEDULED_HHMM, hhmm),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        }
    }

    private fun actionPending(
        ctx: Context, notifId: Int, id: String, name: String, dose: String,
        hhmm: String, action: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        (id + hhmm + action).hashCode(),
        Intent(ctx, MedicationReminderReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_NOTIF_ID, notifId)
            .putExtra(MedicationScheduler.EXTRA_MED_ID, id)
            .putExtra(MedicationScheduler.EXTRA_MED_NAME, name)
            .putExtra(MedicationScheduler.EXTRA_MED_DOSE, dose)
            .putExtra(MedicationScheduler.EXTRA_SCHEDULED_HHMM, hhmm),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Medication reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Scheduled medication dose alerts."
                setShowBadge(true)
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "biosense_meds"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val ACTION_TAKEN = "com.biosense.action.MED_TAKEN"
        const val ACTION_SKIPPED = "com.biosense.action.MED_SKIPPED"
        const val ACTION_SNOOZE = "com.biosense.action.MED_SNOOZE"
    }
}
