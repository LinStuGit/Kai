package com.kami.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Runs one agent turn in the background when an action=agent reminder fires.
 * Alarms scheduled via setAlarmClock get a temporary FGS background-start
 * window when they fire; progress and the final answer are reported through
 * the notification bar (no UI). Each reminder id runs at most once at a time.
 */
class AgentJobService : Service() {

    private val inFlight = ConcurrentHashMap<String, Unit>()

    @Volatile
    private var progressText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    // 批 14 教训：服务已存活时再次 start 不重走 onCreate，onStartCommand 必须每次 goForeground
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val id = intent?.getStringExtra("id").orEmpty()
        val r = ReminderStore.get(id)
        val prompt = r?.prompt?.ifBlank { null } ?: r?.title.orEmpty()
        if (prompt.isBlank() || inFlight.putIfAbsent(id, Unit) != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        progressText = prompt.take(120)
        thread {
            try {
                // 闹钟可能直接唤醒进程（无 MainActivity），补齐存储初始化
                ReminderStore.init(this@AgentJobService)
                SessionStore.init(this@AgentJobService)
                val reply = try {
                    AgentClient.turn("job:" + id, this@AgentJobService, JSONArray(), prompt) { ev ->
                        progressText = ev.take(120)
                        notifyBar("Kami 定时任务运行中", progressText, ongoing = true)
                    }
                } catch (t: Throwable) {
                    notifyBar(
                        "定时任务失败：" + (r?.title ?: id),
                        (t.message ?: t.javaClass.simpleName).take(300),
                        ongoing = false,
                    )
                    Vibe.once(this@AgentJobService, 400)
                    return@thread
                }
                notifyBar("定时任务完成：" + (r?.title ?: id), reply.content.take(300), ongoing = false)
                Vibe.once(this@AgentJobService, 400)
            } finally {
                inFlight.remove(id)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, JOB_CHANNEL)
            .setSmallIcon(if (ongoing) android.R.drawable.ic_media_play else android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun notifyBar(title: String, text: String, ongoing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(JOB_NOTIF_ID, buildNotification(title, text, ongoing))
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(JOB_CHANNEL, "定时 Agent 任务", NotificationManager.IMPORTANCE_LOW).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200, 150, 200)
                },
            )
        }
        // startForeground 既展示通知又完成 FGS 提权，无须再 nm.notify
        startForeground(JOB_NOTIF_ID, buildNotification("Kami 定时任务运行中", progressText.ifBlank { "准备中…" }, ongoing = true))
    }

    companion object {
        private const val JOB_CHANNEL = "kami_jobs"
        private const val JOB_NOTIF_ID = 41001
    }
}
