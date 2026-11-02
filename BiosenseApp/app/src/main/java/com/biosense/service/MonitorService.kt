package com.biosense.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material3.ExperimentalMaterial3Api
import com.biosense.MainActivity
import com.biosense.R
import com.biosense.notification.FallSosReceiver

/**
 * Foreground service whose sole job is to keep the app's process alive so the
 * BLE connection (owned by BleManager inside MonitorViewModel) survives screen-off
 * and app-background states. Required for overnight sleep tracking — Android
 * aggressively kills background BLE within a few minutes otherwise.
 *
 * The service does not own BLE state itself; it just holds a persistent
 * notification + partial wake lock while running. Start when a session that
 * needs guaranteed uptime begins (e.g. sleep tracking), stop when it ends.
 */
@OptIn(ExperimentalMaterial3Api::class)
class MonitorService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopSelfCleanly() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Biosense:BleSession").apply {
            setReferenceCounted(false)
            acquire(12L * 60L * 60L * 1000L) // safety cap: 12 hours
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Biosense monitoring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the wearable connection alive during monitoring/sleep sessions."
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Biosense monitoring")
            .setContentText("Holding wearable connection.")
            .setOngoing(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "biosense_monitor"
        private const val ALERT_CHANNEL_ID = "biosense_alerts"
        private const val NOTIF_ID = 42
        private const val FALL_NOTIF_ID = 43
        private const val HR_ALERT_NOTIF_ID = 44
        const val ACTION_STOP = "com.biosense.action.STOP_MONITOR"

        fun start(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Raise a high-priority alert notification when the wearable reports a
         * fall. Uses a separate high-importance channel so it makes sound / heads
         * up even if the monitoring notification is muted.
         */
        fun raiseFallAlert(ctx: Context, atMs: Long) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Biosense alerts",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Safety alerts from the wearable (falls, low battery)."
                        setShowBadge(true)
                    }
                    nm.createNotificationChannel(ch)
                }
            }
            val openApp = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val sos = PendingIntent.getBroadcast(
                ctx, 10,
                Intent(ctx, FallSosReceiver::class.java)
                    .setAction(FallSosReceiver.ACTION_SOS)
                    .putExtra(FallSosReceiver.EXTRA_NOTIF_ID, FALL_NOTIF_ID),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val ok = PendingIntent.getBroadcast(
                ctx, 11,
                Intent(ctx, FallSosReceiver::class.java)
                    .setAction(FallSosReceiver.ACTION_OK)
                    .putExtra(FallSosReceiver.EXTRA_NOTIF_ID, FALL_NOTIF_ID),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Fall detected")
                .setContentText("The wearable reported a fall — tap SOS to call your emergency contact.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .setWhen(atMs)
                .addAction(0, "SOS", sos)
                .addAction(0, "I'm OK", ok)
                .build()
            NotificationManagerCompat.from(ctx).notify(FALL_NOTIF_ID, n)
        }

        /** Fires when HR sustains outside the profile's alert bounds. */
        fun raiseHrAlert(ctx: Context, bpm: Int, kind: HrAlertKind) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "Biosense alerts",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                    nm.createNotificationChannel(ch)
                }
            }
            val openApp = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = when (kind) {
                HrAlertKind.HIGH -> "High heart rate"
                HrAlertKind.LOW -> "Low heart rate"
            }
            val body = when (kind) {
                HrAlertKind.HIGH -> "Wearer's HR held at $bpm bpm — above your alert threshold."
                HrAlertKind.LOW -> "Wearer's HR held at $bpm bpm — below your alert threshold."
            }
            val n = NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp)
                .build()
            NotificationManagerCompat.from(ctx).notify(HR_ALERT_NOTIF_ID, n)
        }
    }

    enum class HrAlertKind { HIGH, LOW }
}
