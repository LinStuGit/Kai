package com.kami.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Optional watchdog (看门狗). Unlike [KeepAlive]'s system state — which keeps
 * no process alive — this is a real foreground service that stays resident
 * in the notification bar once switched on, so scheduled work and the agent
 * keep a warm process to land on. START_STICKY means the system restarts it
 * if it is ever killed; it is entirely user-optional and off by default.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Kami 守护",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIF_ID) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("Kami 守护")
        .setContentText("运行中 · 定时任务与后台操作可靠触发")
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL = "kami_watchdog"
        private const val NOTIF_ID = 3001
        private const val PREFS = "kami_watchdog"
        private const val KEY = "on"

        fun enabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)

        /** Toggle: start the foreground watchdog on, or stop it and forget. */
        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, on).apply()
            if (on) {
                runCatching {
                    ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
                }
            } else {
                runCatching {
                    ctx.stopService(Intent(ctx, KeepAliveService::class.java))
                }
            }
        }

        /** Called at app start and on boot-complete: keep the watchdog if on. */
        fun applyIfEnabled(ctx: Context) {
            if (enabled(ctx)) {
                runCatching {
                    ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
                }
            }
        }
    }
}