package com.inspiredandroid.kai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.RemoteDataRepository
import com.inspiredandroid.kai.data.ThemeMode
import com.inspiredandroid.kai.tools.AgentOverlayController
import com.inspiredandroid.kai.ui.DarkColorScheme
import com.inspiredandroid.kai.ui.LightColorScheme
import com.inspiredandroid.kai.ui.Theme
import com.inspiredandroid.kai.ui.chat.History
import com.inspiredandroid.kai.ui.withBlackBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service that draws a small draggable overlay while the agent runs
 * tools (e.g. Shizuku shell commands) and the user is outside the app: live
 * thinking preview, the tools currently executing, and a stop button. Started
 * from [com.inspiredandroid.kai.tools.notifyAgentRunActive]; stops itself when
 * the run ends or the user returns to the app (view detached, service kept
 * until the run finishes).
 */
class AgentOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val appSettings: AppSettings by inject()
    private val dataRepository: RemoteDataRepository by inject()

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private var overlayView: ComposeView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        attachOverlay()

        serviceScope.launch {
            AgentOverlayController.runState.collect { state ->
                if (!state.active) stopSelf()
            }
        }
        serviceScope.launch {
            AgentOverlayController.activityVisible.collect { visible ->
                if (visible) detachOverlay() else attachOverlay()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detachOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@AgentOverlayService)
            setViewTreeSavedStateRegistryOwner(this@AgentOverlayService)
            setContent {
                OverlayApp(
                    appSettings = appSettings,
                    history = dataRepository.chatHistory.collectAsState().value,
                    onStop = {
                        AgentOverlayController.onCancel?.invoke()
                    },
                    onDrag = { dx, dy -> dragOverlay(dx, dy) },
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels / 4
            y = resources.displayMetrics.heightPixels / 4
        }
        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            return
        }
        overlayView = view
        overlayParams = params
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        overlayParams = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    private fun dragOverlay(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kami 悬浮窗",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "agent 在后台执行任务时显示悬浮进度"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setContentTitle("Kami 正在后台执行任务")
            .setContentText("工具执行中，点按返回应用")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kai_overlay_channel"
        private const val NOTIFICATION_ID = 9002
    }
}

/** App-consistent theming for the overlay, mirroring MainActivity's scheme selection. */
@Composable
private fun OverlayApp(
    appSettings: AppSettings,
    history: List<History>,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val themeMode by appSettings.themeModeFlow.collectAsState()
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.System -> systemInDark
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.OledBlack -> true
    }
    val context = LocalContext.current
    val dynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        isDark && dynamicColor -> dynamicDarkColorScheme(context)
        isDark -> DarkColorScheme
        dynamicColor -> dynamicLightColorScheme(context)
        else -> LightColorScheme
    }.let { if (themeMode == ThemeMode.OledBlack && isDark) it.withBlackBackground() else it }

    Theme(colorScheme = colorScheme) {
        OverlayContent(
            history = history,
            onStop = onStop,
            onDrag = onDrag,
        )
    }
}

@Composable
private fun OverlayContent(
    history: List<History>,
    onStop: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    val executing = history.filter { it.role == History.Role.TOOL_EXECUTING }
    val latestThinking = history.lastOrNull { it.isThinking && it.content.isNotBlank() }?.content.orEmpty()
    var expanded by remember { mutableStateOf(true) }

    Surface(
        modifier = Modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Kami 正在执行",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 6.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.size(8.dp))
                if (executing.isNotEmpty()) {
                    executing.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "·",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = row.toolName?.ifBlank { row.content } ?: row.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text(
                        text = "思考中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (latestThinking.isNotBlank()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = latestThinking.takeLast(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onStop) {
                        Text(
                            text = "终止",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
