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
import android.util.Base64

/**
 * Optional watchdog (看门狗). Unlike [KeepAlive]'s system state — which keeps
 * no process alive — this is a real foreground service that stays resident
 * in the notification bar once switched on, so scheduled work and the agent
 * keep a warm process to land on. START_STICKY means the system restarts it
 * if it is ever killed; it is entirely user-optional and off by default.
 *
 * On top of the FGS, enabling it also spawns a **detached shell daemon**
 * through Shizuku (a plain `sh` loop owned by the shell uid, outside the
 * app process — the same trick Shizuku itself uses to survive): swiping the
 * app away cannot touch it. Every 15s it re-applies the Doze whitelist /
 * background appop and, if the app process is gone, brings the foreground
 * service right back with `am start-foreground-service`.
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must follow EVERY startForegroundService call — when the service is
        // already running (e.g. user reopens the app while the watchdog FGS
        // from earlier is alive) onCreate is not invoked again, and skipping
        // this crashes with ForegroundServiceDidNotStartInTime.
        goForeground()
        return START_STICKY
    }

    private fun goForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

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
        private const val PKG = "com.kami.app"

        // Shell-side state, so the detached daemon (which cannot read app
        // prefs) knows whether it should keep running.
        private const val MARKER = "/data/local/tmp/.kami-watchdog-on"
        private const val SCRIPT = "/data/local/tmp/.kami-watchdog.sh"
        private const val PID_FILE = "/data/local/tmp/.kami-watchdog.pid"

        fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

        /** Toggle: start the foreground watchdog on, or stop it and forget. */
        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, on).apply()
            if (on) {
                runCatching {
                    ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
                }
                Thread { syncDaemon() }.start()
            } else {
                runCatching {
                    ctx.stopService(Intent(ctx, KeepAliveService::class.java))
                }
                Thread { stopDaemon() }.start()
            }
        }

        /** Called at app start and on boot-complete: keep the watchdog if on. */
        fun applyIfEnabled(ctx: Context) {
            if (enabled(ctx)) {
                runCatching {
                    ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
                }
                Thread { syncDaemon() }.start()
            }
        }

        /** True when the detached shell daemon is alive (needs Shizuku). */
        fun daemonRunning(): Boolean = ShizukuRunner.granted() && ShizukuRunner.run(
            "kill -0 \$(cat $PID_FILE 2>/dev/null) 2>/dev/null && echo up; true",
        ).contains("up")

        /** Write the marker and (re)spawn the detached daemon. */
        private fun syncDaemon() {
            if (!ShizukuRunner.granted()) return
            ShizukuRunner.run("echo 1 > $MARKER")
            spawnDaemon()
        }

        /** Drop the marker and kill the detached daemon (pattern uses a
         *  character class so it cannot match the invoking command itself). */
        private fun stopDaemon() {
            if (!ShizukuRunner.granted()) return
            ShizukuRunner.run("rm -f $MARKER; pkill -f 'kami-watchdog[.]sh' 2>/dev/null; true")
        }

        /** Push the loop script and launch it detached: nohup + setsid +
         *  backgrounded, so it outlives this app process being swiped away. */
        private fun spawnDaemon() {
            val b64 = Base64.encodeToString(scriptText().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            ShizukuRunner.run(
                "pkill -f 'kami-watchdog[.]sh' 2>/dev/null; " +
                    "printf %s $b64 | base64 -d > $SCRIPT && chmod 700 $SCRIPT; " +
                    "nohup setsid sh $SCRIPT >/dev/null 2>&1 &",
            )
        }

        /**
         * The daemon loop: singleton-guarded by its pid file; exits as soon
         * as the marker is gone; each cycle re-asserts the Doze exemptions
         * (they do not always survive reboot/OEM cleanup) and revives the
         * app's foreground service if the whole process disappeared.
         */
        private fun scriptText(): String = "#!/system/bin/sh\n" +
            "if [ -f $PID_FILE ] && kill -0 \"\$(cat $PID_FILE 2>/dev/null)\" 2>/dev/null; " +
            "then exit 0; fi\n" +
            "echo \$\$ > $PID_FILE\n" +
            "while true; do\n" +
            "  if [ ! -f $MARKER ]; then rm -f $PID_FILE; exit 0; fi\n" +
            "  dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1\n" +
            "  cmd appops set $PKG RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1\n" +
            "  if ! pidof $PKG >/dev/null 2>&1; then\n" +
            "    am start-foreground-service -n $PKG/.KeepAliveService >/dev/null 2>&1\n" +
            "  fi\n" +
            "  sleep 15\n" +
            "done\n"
    }
}
