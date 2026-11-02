package com.biosense.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.biosense.data.MedicationStore
import java.util.Calendar

/**
 * Turns each medication's HH:MM schedule into an AlarmManager wake-up that
 * fires MedicationReminderReceiver. Alarms are exact (setExactAndAllowWhileIdle)
 * because consistent dose timing matters for many prescriptions — some lose
 * effect if doses drift 30+ min. On Android 12+ we require SCHEDULE_EXACT_ALARM.
 *
 * Request codes are hash(medId, hhmm) so cancelling / rescheduling stays
 * deterministic across restarts.
 */
object MedicationScheduler {

    private const val ACTION_FIRE = "com.biosense.action.MED_FIRE"
    const val EXTRA_MED_ID = "med_id"
    const val EXTRA_MED_NAME = "med_name"
    const val EXTRA_MED_DOSE = "med_dose"
    const val EXTRA_SCHEDULED_HHMM = "scheduled_hhmm"

    fun rescheduleAll(ctx: Context) {
        val store = MedicationStore.of(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Cancel everything we can identify by re-deriving request codes, then
        // schedule the enabled ones. Disabled meds fall through — their old
        // alarms have already been cancelled below.
        val all = store.list()
        for (m in all) {
            for (hhmm in m.timeList()) {
                cancelOne(ctx, am, m.id, hhmm)
                if (m.enabled) scheduleOne(ctx, am, m, hhmm)
            }
        }
    }

    fun cancelForMedication(ctx: Context, med: MedicationStore.Medication) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (hhmm in med.timeList()) cancelOne(ctx, am, med.id, hhmm)
    }

    private fun scheduleOne(ctx: Context, am: AlarmManager, m: MedicationStore.Medication, hhmm: String) {
        val trigger = nextTriggerMs(hhmm) ?: return
        val pi = pending(ctx, m, hhmm, PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                return
            }
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
    }

    private fun cancelOne(ctx: Context, am: AlarmManager, medId: String, hhmm: String) {
        val pi = PendingIntent.getBroadcast(
            ctx,
            requestCode(medId, hhmm),
            fireIntent(ctx, medId, "", "", hhmm),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { am.cancel(it); it.cancel() }
    }

    private fun pending(ctx: Context, m: MedicationStore.Medication, hhmm: String, flags: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            requestCode(m.id, hhmm),
            fireIntent(ctx, m.id, m.name, m.dose, hhmm),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun fireIntent(ctx: Context, id: String, name: String, dose: String, hhmm: String): Intent =
        Intent(ctx, MedicationReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_MED_ID, id)
            .putExtra(EXTRA_MED_NAME, name)
            .putExtra(EXTRA_MED_DOSE, dose)
            .putExtra(EXTRA_SCHEDULED_HHMM, hhmm)

    private fun requestCode(medId: String, hhmm: String): Int =
        ("$medId|$hhmm").hashCode()

    /** Next epoch-ms at [hhmm] — today if in the future, else tomorrow. */
    fun nextTriggerMs(hhmm: String, now: Long = System.currentTimeMillis()): Long? {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val mn = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (h !in 0..23 || mn !in 0..59) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, mn)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}
