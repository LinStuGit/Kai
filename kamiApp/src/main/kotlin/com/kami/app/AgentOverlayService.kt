package com.kami.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared agent-run state: the chat screen registers its running jobs here,
 * the overlay service polls it (500ms) and shows a floating window with the
 * live output plus a force-stop button whenever the user leaves the app
 * mid-run. Plain classic views on purpose — no Compose in a Service.
 */
object AgentOverlayState {

    val running = mutableStateOf(false)
    val status = mutableStateOf("")
    val activityVisible = mutableStateOf(true)

    /** Set while the agent reads the screen / screenshots: hide the window. */
    val suppress = mutableStateOf(false)

    /**
     * >0 while a screen-control tool is actually running. Non-screen work
     * stays in the notification shade; the floating window is reserved for
     * when the agent is driving the screen.
     */
    val screenBusy = mutableStateOf(0)

    /** Console quick actions classified as screen ops force the window on. */
    val forceWindow = mutableStateOf(false)

    val jobs = CopyOnWriteArrayList<Job>()

    fun add(job: Job) {
        jobs.add(job)
        running.value = true
    }

    /** Drop finished/cancelled jobs and recompute [running]. */
    fun refresh() {
        jobs.removeAll { it.isCancelled || it.isCompleted }
        running.value = jobs.isNotEmpty()
    }

    /** Force-stop every running session (overlay button). */
    fun cancelAll() {
        jobs.forEach { it.cancel() }
        running.value = false
    }
}

class AgentOverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    private var attached = false
    private var lastNotifiedStatus: String? = null
    private lateinit var statusView: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (!AgentOverlayState.running.value) {
                detach()
                stopSelf()
                return
            }
            val show = !AgentOverlayState.activityVisible.value &&
                !AgentOverlayState.suppress.value &&
                (AgentOverlayState.screenBusy.value > 0 || AgentOverlayState.forceWindow.value)
            if (show && !attached) attach()
            if (!show && attached) detach()
            val active = AgentOverlayState.jobs.count { it.isActive }
            val st = (if (active > 1) "[$active 个会话] " else "") +
                AgentOverlayState.status.value.ifEmpty { "思考中…" }
            if (attached) {
                statusView.text = st
            }
            // Non-screen work reports progress in the notification shade.
            if (st != lastNotifiedStatus) {
                lastNotifiedStatus = st
                notifyProgress(st)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "kami_overlay",
                    "Kami Agent 悬浮窗",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = Notification.Builder(this, "kami_overlay")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kami Agent 运行中")
            .setContentText("切走时悬浮窗显示输出，可强制终止")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun attach() {
        if (!Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE101418.toInt())
                cornerRadius = 16f * density
            }
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Kami Agent"
            setTextColor(0xFF9BD1FF.toInt())
            textSize = 12f
        }
        statusView = TextView(this).apply {
            setTextColor(0xFFD5E0EA.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val stop = Button(this).apply {
            text = "强制终止"
            textSize = 12f
            setOnClickListener { AgentOverlayState.cancelAll() }
        }
        box.addView(title)
        box.addView(statusView)
        box.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pad
            y = pad * 5 // stay clear of the status bar
        }
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, params)
        view = box
        attached = true
    }

    private fun detach() {
        if (!attached) return
        attached = false
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        }
        view = null
    }

    /** Update the foreground notification with the latest status line. */
    private fun notifyProgress(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, "kami_overlay")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kami Agent 运行中")
            .setContentText(text)
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detach()
        lastNotifiedStatus = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2001
    }
}
