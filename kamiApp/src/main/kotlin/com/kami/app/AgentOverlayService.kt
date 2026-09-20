package com.kami.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Rich content shown inside the overlay panel above everything else: a web
 * page (url), a raw HTML snippet, or a local image file — whatever the agent
 * wants the user to see (charts, previews, results).
 */
data class OverlayContent(val kind: String, val data: String, val title: String)

/**
 * Shared agent-run state: the chat screen registers its running jobs here,
 * the overlay service polls it (500ms) and shows a small draggable floating
 * ball (tap to expand status + force-stop) whenever the user leaves the app
 * mid-run. Plain classic views on purpose — no Compose in a Service.
 */
object AgentOverlayState {

    val running = mutableStateOf(false)
    val status = mutableStateOf("")
    val activityVisible = mutableStateOf(true)

    /** Current rich content (null = none); the service polls and renders it. */
    val content = mutableStateOf<OverlayContent?>(null)

    /** Set while the agent reads the screen: collapse the panel to the ball. */
    val suppress = mutableStateOf(false)

    /**
     * >0 while a screen-control tool is actually running. Non-screen work
     * stays in the notification shade; the floating ball is reserved for
     * when the agent is driving the screen.
     */
    val screenBusy = mutableStateOf(0)

    /** Console quick actions classified as screen ops force the ball on. */
    val forceWindow = mutableStateOf(false)

    /**
     * Sticky: once a run has touched the screen it keeps the ball for the
     * rest of the run — tool gaps (model thinking between calls) must not
     * make the ball flicker away. Reset when every job is done.
     */
    val screenSeen = mutableStateOf(false)

    /** Show rich content in the overlay (and make sure the service runs). */
    fun showContent(spec: OverlayContent) {
        content.value = spec
        keepAlive()
    }

    fun clearContent() {
        content.value = null
    }

    private fun keepAlive() {
        val ctx = AppContextHolder.get()
        ctx.startForegroundService(Intent(ctx, AgentOverlayService::class.java))
    }

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

    /** Force-stop every running session (ball panel / notification / home).
     *  Unconditional: cancelled cooperatively AND whatever they are blocked
     *  on is torn down at once — the in-flight HTTP read is disconnected,
     *  pending biometric prompts settle as denied, and the in-flight
     *  Shizuku command trees are SIGKILLed. */
    fun cancelAll() {
        jobs.forEach { it.cancel() }
        running.value = false
        screenSeen.value = false
        AgentClient.abortAll()
        SensitiveGate.cancelPending()
        Thread { ShizukuRunner.killAll() }.start()
        // Forced stop must also hand the user's own keyboard back if we
        // borrowed it mid-run.
        Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
    }
}

class AgentOverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var ball: View? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false

    /** User tapped the ball open — screen reads must not collapse it. */
    private var userExpanded = false
    private var attached = false
    private var lastNotifiedStatus: String? = null
    private lateinit var statusView: TextView
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Rich-content views: container inside the panel + current spec. */
    private var contentHolder: LinearLayout? = null
    private var contentView: View? = null
    private var renderedSpec: OverlayContent? = null

    private val tick = object : Runnable {
        override fun run() {
            // Self-heal: recompute running from the real job states so a
            // stale flag can never keep the ball or this service alive
            // with no work behind it.
            AgentOverlayState.refresh()
            // Rich content (web page / html / image) takes priority: shown
            // above everything, staying up even with no agent run active.
            val spec = AgentOverlayState.content.value
            if (spec != null) {
                if (!attached) attach()
                if (attached) {
                    renderContent(spec)
                    setExpanded(true)
                    handler.postDelayed(this, 500)
                    return
                }
            } else if (contentView != null) {
                removeContent()
            }
            if (!AgentOverlayState.running.value) {
                val hadScreen = AgentOverlayState.screenSeen.value
                AgentOverlayState.screenSeen.value = false
                // A finished screen-driving run: expand the panel with a
                // completion note (the user is likely elsewhere), linger a
                // few seconds, then stop the service.
                if (hadScreen && expandForCompletion()) {
                    statusView.text = "任务已完成"
                    notifyProgress("任务已完成")
                    handler.postDelayed({
                        detach()
                        Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
                        stopSelf()
                    }, 8000)
                    return
                }
                detach()
                Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
                stopSelf()
                return
            }
            val screenActive = AgentOverlayState.screenBusy.value > 0 ||
                AgentOverlayState.forceWindow.value
            if (screenActive) AgentOverlayState.screenSeen.value = true
            // Sticky: once this run drove the screen, keep the ball until
            // the run ends (no flicker between tool calls).
            val show = !AgentOverlayState.activityVisible.value &&
                (screenActive || AgentOverlayState.screenSeen.value)
            if (show && !attached) attach()
            if (!show && attached) detach()
            // Screen reads collapse the panel back to the ball — unless the
            // user opened it themselves (they asked to see it).
            if (AgentOverlayState.suppress.value && !userExpanded) setExpanded(false)
            // Keep the display on for the whole screen-driving run.
            if (show) ensureWakeLock() else releaseWakeLock()
            val active = AgentOverlayState.jobs.count { it.isActive }
            val st = (if (active > 1) "[$active 个会话] " else "") +
                AgentOverlayState.status.value.ifEmpty { "思考中…" }
            if (attached) {
                statusView.text = st
            }
            // Every run reports progress in the notification shade.
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
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService call while the service is already running
        // (new run starting during the completion-linger window) must still
        // be answered with startForeground or the system crashes the app.
        goForeground()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val notif = buildNotification("运行进度在此通知更新")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun attach() {
        if (!Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("kami_overlay", Context.MODE_PRIVATE)
        val dm = resources.displayMetrics

        // Expanded panel: status + force-stop. Hidden until the ball is tapped.
        statusView = TextView(this).apply {
            setTextColor(0xFFD5E0EA.toInt())
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val title = TextView(this).apply {
            text = "Kami Agent"
            setTextColor(0xFF9BD1FF.toInt())
            textSize = 12f
        }
        val collapse = TextView(this).apply {
            text = "收起"
            setTextColor(0xFF8A97A3.toInt())
            textSize = 12f
            setOnClickListener {
                userExpanded = false
                setExpanded(false)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(collapse)
        }
        val stop = Button(this).apply {
            text = "强制终止"
            textSize = 12f
            setOnClickListener { AgentOverlayState.cancelAll() }
        }
        val contentClose = TextView(this).apply {
            text = "✕ 关闭内容"
            setTextColor(0xFF8A97A3.toInt())
            textSize = 12f
            setOnClickListener { AgentOverlayState.clearContent() }
        }
        contentHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(contentClose)
        }
        val cardBg = GradientDrawable().apply {
            setColor(0xEE101418.toInt())
            cornerRadius = 14f * resources.displayMetrics.density
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            visibility = View.GONE
            addView(head)
            addView(statusView)
            addView(stop)
            addView(contentHolder)
        }

        // The ball: a small draggable circle, tap (no drag) to expand.
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF9BD1FF.toInt())
            }
        }
        val ballSize = dp(38f)
        ball = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC101418.toInt())
            }
            addView(
                dot,
                FrameLayout.LayoutParams(dp(10f), dp(10f), Gravity.CENTER),
            )
            setOnTouchListener(makeBallDragListener(ballSize))
        }

        root = FrameLayout(this).apply {
            addView(panel, FrameLayout.LayoutParams(dp(230f), FrameLayout.LayoutParams.WRAP_CONTENT))
            addView(
                ball,
                FrameLayout.LayoutParams(ballSize, ballSize),
            )
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(16f)).coerceIn(0, dm.widthPixels - ballSize)
            y = prefs.getInt("y", dp(96f)).coerceIn(0, dm.heightPixels - ballSize)
        }
        wm.addView(root, params)
        attached = true
    }

    /** Drag moves the ball (position persisted); a plain tap toggles the panel. */
    private fun makeBallDragListener(ballSize: Int): View.OnTouchListener {
        val dm = resources.displayMetrics
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return View.OnTouchListener { v, e ->
            val p = params ?: return@OnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragging || dx * dx + dy * dy > 20 * 20) {
                        dragging = true
                        p.x = (startX + dx).toInt().coerceIn(0, dm.widthPixels - ballSize)
                        p.y = (startY + dy).toInt().coerceIn(0, dm.heightPixels - ballSize)
                        runCatching {
                            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .updateViewLayout(root, p)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        getSharedPreferences("kami_overlay", Context.MODE_PRIVATE)
                            .edit().putInt("x", p.x).putInt("y", p.y).apply()
                    } else {
                        userExpanded = !expanded
                        setExpanded(!expanded)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        panel?.visibility = if (value) View.VISIBLE else View.GONE
        ball?.visibility = if (value) View.GONE else View.VISIBLE
    }

    /** Build/refresh the rich-content view inside the panel. */
    private fun renderContent(spec: OverlayContent) {
        if (renderedSpec == spec && contentView != null) return
        removeContent()
        val holder = contentHolder ?: return
        val view: View = when (spec.kind) {
            "image" -> {
                val iv = android.widget.ImageView(this)
                val bmp = try {
                    android.graphics.BitmapFactory.decodeFile(spec.data)
                } catch (t: Throwable) {
                    null
                }
                if (bmp != null) {
                    iv.setImageBitmap(bmp)
                } else {
                    iv.minimumHeight = dp(60f)
                    iv.background = GradientDrawable().apply { setColor(0x33101418.toInt()) }
                    // ImageView can't hold text; wrap the error in a TextView.
                    holder.addView(
                        TextView(this).apply {
                            text = "无法读取图片：${spec.data}"
                            setTextColor(0xFFFFB4A9.toInt())
                            textSize = 11f
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
                iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                iv
            }

            else -> {
                val wv = android.webkit.WebView(this)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = true
                if (spec.kind == "url") {
                    wv.loadUrl(spec.data)
                } else {
                    wv.loadDataWithBaseURL(null, spec.data, "text/html", "utf-8", null)
                }
                wv
            }
        }
        val h = if (spec.kind == "image") dp(320f) else dp(380f)
        contentView = view
        renderedSpec = spec
        holder.addView(
            view,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h),
        )
        holder.visibility = View.VISIBLE
        // Widen the panel so pages/images have room.
        panel?.layoutParams = FrameLayout.LayoutParams(dp(280f), FrameLayout.LayoutParams.WRAP_CONTENT)
        panel?.requestLayout()
        root?.let { r ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(r, params)
            }
        }
    }

    private fun removeContent() {
        contentView?.let { v ->
            if (v is android.webkit.WebView) {
                runCatching { v.stopLoading() }
                runCatching { v.destroy() }
            }
        }
        contentView = null
        renderedSpec = null
        contentHolder?.let { h ->
            h.removeAllViews()
            h.visibility = View.GONE
        }
        panel?.layoutParams = FrameLayout.LayoutParams(dp(230f), FrameLayout.LayoutParams.WRAP_CONTENT)
        panel?.requestLayout()
    }

    /** SCREEN_BRIGHT for the whole screen-driving run, refreshed every tick. */
    private fun ensureWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val lock = wakeLock ?: run {
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kami:overlay-run",
            ).also { wakeLock = it }
        }
        runCatching { lock.acquire(10 * 60_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /** Attach (once) and expand the panel for the completion notice;
     *  false when there is no overlay permission to show anything. */
    private fun expandForCompletion(): Boolean {
        if (!attached) attach()
        if (!attached) return false
        setExpanded(true)
        return true
    }

    private fun detach() {
        if (!attached) return
        attached = false
        userExpanded = false
        setExpanded(false)
        removeContent()
        runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(root)
        }
        root = null
        ball = null
        panel = null
    }

    private fun stopPi(): PendingIntent = PendingIntent.getBroadcast(
        this,
        0,
        Intent(this, StopReceiver::class.java).setAction(StopReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String): Notification = Notification.Builder(this, "kami_overlay")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Kami Agent 运行中")
        .setContentText(text)
        .setOngoing(true)
        .addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(
                    this,
                    android.R.drawable.ic_menu_close_clear_cancel,
                ),
                "强制终止",
                stopPi(),
            ).build(),
        )
        .build()

    /** Update the foreground notification with the latest status line. */
    private fun notifyProgress(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detach()
        releaseWakeLock()
        AgentOverlayState.screenSeen.value = false
        lastNotifiedStatus = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2001
    }
}
