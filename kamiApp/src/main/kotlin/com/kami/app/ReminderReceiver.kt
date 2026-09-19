package com.kami.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Fires scheduled reminders: posts a system notification (important ones
 * vibrate and use a full-screen intent straight into the app), then arms
 * the next day's occurrence. Also re-arms everything after boot.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        ReminderStore.init(appCtx)

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.scheduleAll(appCtx)
            // Wake the optional watchdog back up after a reboot, and re-apply
            // the Shizuku doze/background exemptions in case they were cleared.
            KeepAliveService.applyIfEnabled(appCtx)
            Thread { runCatching { KeepAlive.apply() } }.start()
            return
        }

        val r = ReminderStore.get(intent.getStringExtra("id") ?: return) ?: return
        postNotification(appCtx, r)
        ReminderScheduler.scheduleAll(appCtx)
    }

    private fun postNotification(ctx: Context, r: Reminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "定时任务", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(r.important)
                },
            )
        }

        // Tapping (or full-screen) lands in the app itself.
        val contentPi = PendingIntent.getActivity(
            ctx,
            r.id.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (r.important) {
            builder.setFullScreenIntent(contentPi, true)
            builder.setVibrate(longArrayOf(0, 350, 250, 350))
        }
        nm.notify(r.id.hashCode(), builder.build())
    }

    companion object {
        private const val CHANNEL = "kami_reminders"
    }
}
