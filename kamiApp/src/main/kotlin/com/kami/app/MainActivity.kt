package com.kami.app

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

private const val REQ_SHIZUKU = 7001

private const val BANNER =
    "Kami ADB console — 命令经 Shizuku 以 shell uid 执行\n" +
        "支持 ; / && 串联与管道，无 root；agent 对话可另用 Alpine proot 沙箱\n"

/** One-tap preset commands shown as chips. */
private data class QuickAction(val label: String, val cmd: String)

private val QUICK_ACTIONS = listOf(
    QuickAction(
        "开无线调试",
        "settings put global adb_wifi_enabled 1 && sleep 2 && getprop service.adb.tls.port",
    ),
    QuickAction("查 ADB 端口", "getprop service.adb.tls.port"),
    QuickAction("电池白名单", "dumpsys deviceidle whitelist +com.kami.app"),
    QuickAction("后台放行", "cmd appops set com.kami.app RUN_ANY_IN_BACKGROUND allow"),
    QuickAction(
        "设备信息",
        "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk",
    ),
    QuickAction("WLAN IP", "ip -4 addr show wlan0 | grep inet"),
)

private const val EXT_HINT =
    "agent 用法（PC 端经 adb / Shizuku 下发，结果回读 logcat）：\n" +
        "am broadcast -a com.kami.app.ext.ADD --es json " +
        "'{\"id\":\"cap\",\"name\":\"截屏\",\"cmd\":\"screencap -p /sdcard/kami.png\"}'\n" +
        "am broadcast -a com.kami.app.ext.LIST\n" +
        "logcat -d -s KamiExt"

private const val SKILL_HINT =
    "技能 = 提示词模板（{{变量}} 占位），agent 用 skill_run 展开后执行：\n" +
        "{\"name\":\"早报\",\"desc\":\"生成每日早报\",\"template\":\"现在 {{时间}}，生成今日早报：日程、提醒、待办\"}\n" +
        "也可让 agent 自己通过 add_extension 之外的方式建议模板，由用户导入"

/** Runtime permission groups surfaced in Settings → 权限管理. */
private data class PermGroup(val label: String, val perms: List<String>)

private val RUNTIME_GROUPS: List<PermGroup> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(PermGroup("通知", listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
    add(PermGroup("相机", listOf(Manifest.permission.CAMERA)))
    add(PermGroup("麦克风", listOf(Manifest.permission.RECORD_AUDIO)))
    add(
        PermGroup(
            "定位",
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        ),
    )
    add(PermGroup("后台定位（先授权定位）", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
    add(
        PermGroup(
            "联系人",
            listOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
            ),
        ),
    )
    add(
        PermGroup(
            "日历",
            listOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
            ),
        ),
    )
    add(
        PermGroup(
            "电话",
            listOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ANSWER_PHONE_CALLS,
            ),
        ),
    )
    add(
        PermGroup(
            "短信",
            listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
            ),
        ),
    )
    add(
        if (Build.VERSION.SDK_INT >= 33) {
            PermGroup(
                "媒体文件",
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                ),
            )
        } else {
            PermGroup("媒体文件", listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        },
    )
    if (Build.VERSION.SDK_INT >= 31) {
        add(
            PermGroup(
                "蓝牙",
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                ),
            ),
        )
    }
    add(PermGroup("身体传感器", listOf(Manifest.permission.BODY_SENSORS)))
    add(PermGroup("活动识别", listOf(Manifest.permission.ACTIVITY_RECOGNITION)))
}

/** Special app accesses that live on dedicated system settings screens. */
private data class SpecialAccess(
    val label: String,
    val granted: (Context) -> Boolean,
    val open: (Context) -> Unit,
)

private val SPECIAL_ACCESS: List<SpecialAccess> = listOf(
    SpecialAccess(
        "悬浮窗",
        { Settings.canDrawOverlays(it) },
        { ctx ->
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                ),
            )
        },
    ),
    SpecialAccess(
        "修改系统设置",
        { Settings.System.canWrite(it) },
        { ctx ->
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${ctx.packageName}"),
                ),
            )
        },
    ),
    SpecialAccess(
        "安装未知应用",
        { it.packageManager.canRequestPackageInstalls() },
        { ctx ->
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ),
            )
        },
    ),
    SpecialAccess(
        "所有文件访问",
        { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() },
        { ctx ->
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                ),
            )
        },
    ),
    SpecialAccess(
        "使用情况访问",
        { usageAccessGranted(it) },
        { ctx -> ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
    ),
    SpecialAccess(
        "闹钟与提醒（精确闹钟）",
        {
            Build.VERSION.SDK_INT < 31 ||
                (it.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                    .canScheduleExactAlarms()
        },
        { ctx ->
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${ctx.packageName}")),
                    )
                }
            }
        },
    ),
    SpecialAccess(
        "电池优化白名单",
        { ctx ->
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        },
        { ctx ->
            ctx.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}"),
                ),
            )
        },
    ),
)

private fun usageAccessGranted(ctx: Context): Boolean = try {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    ops.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        ctx.packageName,
    ) == AppOpsManager.MODE_ALLOWED
} catch (t: Throwable) {
    false
}

/** Settings menu entry: title row that opens a sub page. */
private data class SettingsEntry(val title: String, val subtitle: String, val target: String)

private val SETTINGS_ENTRIES = listOf(
    SettingsEntry("Agent 模型", "固定 madmodel 端点 · JWT 池状态", "set-agent"),
    SettingsEntry("定时任务", "每日提醒 · 重要任务震动直达", "set-tasks"),
    SettingsEntry("沙箱管理", "内置 Alpine · 安装与状态", "set-sandbox"),
    SettingsEntry("拓展与技能", "shell 拓展 · 提示词技能模板", "set-ext"),
    SettingsEntry("权限管理", "运行时权限 · 特殊访问", "set-perms"),
    SettingsEntry("安全", "敏感操作生物验证", "set-sec"),
)

private val TIME_RE = Regex("^(\\d{1,2}):(\\d{2})$")

/**
 * Shizuku-backed ADB shell console + on-device agent. Home is the chat
 * screen; console / terminal / settings sub pages sit on top of it.
 * Every Shizuku command runs as shell uid — no root.
 */
class MainActivity : FragmentActivity() {

    private val shizukuAlive = mutableStateOf(false)
    private val shizukuGranted = mutableStateOf(false)

    /** "chat" is the home screen; everything else sits on top of it. */
    private val screen = mutableStateOf("chat")

    private val binderListener = Shizuku.OnBinderReceivedListener {
        if (!ShizukuRunner.granted()) {
            try {
                Shizuku.requestPermission(REQ_SHIZUKU)
            } catch (_: IllegalStateException) {
            }
        }
        refresh()
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private fun refresh() {
        shizukuAlive.value = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        shizukuGranted.value = ShizukuRunner.granted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExtensionStore.init(applicationContext)
        ProotSandbox.init(applicationContext)
        ScreenControl.init(applicationContext)
        MemoryStore.init(applicationContext)
        SkillStore.init(applicationContext)
        ReminderStore.init(applicationContext)
        ReminderScheduler.scheduleAll(applicationContext)
        // targetSdk 35+ enforces edge-to-edge; draw edge to edge on purpose
        // and pad content with safeDrawing (status bar + nav + IME) below.
        enableEdgeToEdge()
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        refresh()
        setContent {
            // Home (chat): double-back within 2s to exit; settings sub pages
            // go back to the settings menu; everything else goes home.
            var lastBackAt by remember { mutableStateOf(0L) }
            BackHandler(enabled = screen.value == "chat") {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) {
                    finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(this@MainActivity, "再按一次返回退出", Toast.LENGTH_SHORT).show()
                }
            }
            BackHandler(enabled = screen.value != "chat") {
                screen.value = if (screen.value.startsWith("set-")) "settings" else "chat"
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        EssentialPermissionsOnce()

                        when (screen.value) {
                            "console" -> ConsoleScreen()

                            "term" -> TerminalScreen(onBack = { screen.value = "console" })

                            "settings" -> SettingsScreen()

                            "set-agent" -> SubPage("Agent 模型") { AgentSection() }

                            "set-tasks" -> SubPage("定时任务") { ReminderSection() }

                            "set-sandbox" -> SubPage("沙箱管理") { SandboxSection() }

                            "set-ext" -> SubPage("拓展与技能") { ExtensionsSection() }

                            "set-perms" -> SubPage("权限管理") { PermissionsSection() }

                            "set-sec" -> SubPage("安全") { BioSection() }

                            else -> ChatScreen(
                                onConsole = { screen.value = "console" },
                                onSettings = { screen.value = "settings" },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AgentOverlayState.activityVisible.value = true
    }

    override fun onStop() {
        super.onStop()
        AgentOverlayState.activityVisible.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    /**
     * Calendar is an essential permission (schedules/reminders) — ask for
     * it plus notifications once on first launch, right on the home screen.
     */
    @Composable
    private fun EssentialPermissionsOnce() {
        val ctx = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
        LaunchedEffect(Unit) {
            val prefs = ctx.getSharedPreferences("kami_essential", Context.MODE_PRIVATE)
            if (prefs.getBoolean("asked", false)) return@LaunchedEffect
            prefs.edit().putBoolean("asked", true).apply()
            val perms = buildList {
                add(Manifest.permission.READ_CALENDAR)
                add(Manifest.permission.WRITE_CALENDAR)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            runCatching { launcher.launch(perms.toTypedArray()) }
        }
    }

    /** Counts ON_RESUME events — sub pages re-read permission/JWT state. */
    @Composable
    private fun rememberResumeRevision(): Int {
        val activity = LocalContext.current as? FragmentActivity
        var revision by remember { mutableStateOf(0) }
        DisposableEffect(activity) {
            val obs = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) revision++
            }
            activity?.lifecycle?.addObserver(obs)
            onDispose { activity?.lifecycle?.removeObserver(obs) }
        }
        return revision
    }

    /** Generic sub page: header back to the settings menu + scrolled content. */
    @Composable
    private fun SubPage(
        title: String,
        content: @Composable () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { screen.value = "settings" }) { Text("← 设置") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }

    @Composable
    private fun ConsoleScreen() {
        val alive by shizukuAlive
        val granted by shizukuGranted
        var input by remember { mutableStateOf("") }
        var transcript by remember { mutableStateOf(BANNER) }
        var running by remember { mutableStateOf(false) }
        val history = remember { mutableListOf<String>() }
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()

        fun run(cmd: String) {
            val c = cmd.trim()
            if (c.isEmpty() || running) return
            input = ""
            if (history.lastOrNull() != c) history.add(c)
            val appCtx = applicationContext
            // Screen-driving commands (screencap/uiautomator/input) earn the
            // floating window; the rest report via the notification shade.
            val screenOp = listOf(
                "screencap",
                "uiautomator",
                "input tap",
                "input swipe",
                "input keyevent",
                "input text",
            ).any { c.contains(it) }
            val job = scope.launch(Dispatchers.IO) {
                running = true
                transcript += "\n→ $c\n"
                try {
                    val t0 = System.currentTimeMillis()
                    val out = ShizukuRunner.run(c)
                    val ms = System.currentTimeMillis() - t0
                    transcript += if (out.isEmpty()) {
                        "（无输出，${ms}ms）\n"
                    } else {
                        "$out\n"
                    }
                } finally {
                    running = false
                    AgentOverlayState.forceWindow.value = false
                    AgentOverlayState.refresh()
                    if (!AgentOverlayState.running.value) {
                        runCatching {
                            appCtx.stopService(Intent(appCtx, AgentOverlayService::class.java))
                        }
                    }
                }
            }
            // Quick actions can drive the screen — track them so the
            // floating window (output + force-stop) shows while the user
            // is in another app.
            AgentOverlayState.status.value = "控制台：$c"
            AgentOverlayState.forceWindow.value = screenOp
            AgentOverlayState.add(job)
            if (Settings.canDrawOverlays(appCtx)) {
                runCatching {
                    ContextCompat.startForegroundService(
                        appCtx,
                        Intent(appCtx, AgentOverlayService::class.java),
                    )
                }
            }
        }

        LaunchedEffect(transcript) { scroll.scrollTo(scroll.maxValue) }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { screen.value = "chat" }) { Text("← 对话") }
                Text(
                    "控制台",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { screen.value = "term" }) { Text("终端") }
                TextButton(onClick = { screen.value = "settings" }) { Text("设置") }
            }

            val (dot, statusText) = when {
                granted -> Color(0xFF4CD97B) to "已授权 — 命令将以 shell 身份执行"
                alive -> Color(0xFFFFC64D) to "Shizuku 在线，等待授权"
                else -> Color(0xFFFF5C6C) to "Shizuku 未运行 — 请先打开 Shizuku APP"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", fontSize = 16.sp, color = dot)
                Text(
                    statusText,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (alive && !granted) {
                    Button(onClick = {
                        try {
                            Shizuku.requestPermission(REQ_SHIZUKU)
                        } catch (_: IllegalStateException) {
                        }
                    }) { Text("授权") }
                } else if (!alive) {
                    OutlinedButton(onClick = { refresh() }) { Text("重试") }
                }
            }

            // Built-ins first, then agent-added extensions, both reactive
            // to ExtensionStore changes.
            val extensionActions = if (ExtensionStore.master.value) {
                ExtensionStore.items.value
                    .filter { it.enabled }
                    .map { QuickAction(it.name, it.cmd) }
            } else {
                emptyList()
            }
            Text("快捷指令", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (QUICK_ACTIONS + extensionActions).forEach { qa ->
                    AssistChip(
                        onClick = { run(qa.cmd) },
                        label = { Text(qa.label) },
                        enabled = granted && !running,
                    )
                }
            }

            Text(
                transcript,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF101418), RoundedCornerShape(10.dp))
                    .verticalScroll(scroll)
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFD5E0EA),
            )

            if (history.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    history.takeLast(4).forEach { h ->
                        AssistChip(
                            onClick = { input = h },
                            label = { Text(h, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("如: getprop ro.product.model", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = granted,
                    trailingIcon = if (running) {
                        { CircularProgressIndicator(Modifier.padding(6.dp)) }
                    } else {
                        null
                    },
                )
                Button(
                    onClick = { run(input) },
                    enabled = granted && !running && input.isNotBlank(),
                ) { Text("执行") }
            }
        }
    }

    /** Settings home: a simple navigation list into the sub pages. */
    @Composable
    private fun SettingsScreen() {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { screen.value = "chat" }) { Text("← 主页") }
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }

            SETTINGS_ENTRIES.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { screen.value = entry.target }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "›",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Kami Console " + runCatching {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    @Suppress("DEPRECATION")
                    "v${pi.versionName} (${pi.versionCode})"
                }.getOrElse { "" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    @Composable
    private fun SandboxSection() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("读取中…") }
        var running by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            status = kotlinx.coroutines.withContext(Dispatchers.IO) { ProotSandbox.status() }
        }

        Text("Alpine 沙箱", style = MaterialTheme.typography.titleSmall)
        Text(
            "proot 与 Alpine rootfs 已内置（arm64/arm），安装无需下载、数秒完成。" +
                "沙箱与手机宿主隔离，apk add 可装软件包；控制台「终端」页可直接进入命令行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            status,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (running) return@Button
                    scope.launch(Dispatchers.IO) {
                        running = true
                        status = "安装中…"
                        status = ProotSandbox.setup()
                        running = false
                    }
                },
                enabled = !running,
            ) { Text("安装/重装沙箱") }
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) { status = ProotSandbox.status() }
                },
                enabled = !running,
            ) { Text("刷新状态") }
        }
        if (running) {
            CircularProgressIndicator(Modifier.padding(6.dp))
        }
        Text(
            "提示：agent 也能用 sandbox_setup/sandbox_run/sandbox_status 工具操作沙箱",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun AgentSection() {
        val revision = rememberResumeRevision()
        Text("Agent 模型（固定接入）", style = MaterialTheme.typography.titleSmall)
        Text(
            "端点  ${MadModel.BASE}\n模型  ${MadModel.MODEL}\n" +
                "key = check 端点签发的 JWT，5 小时自动换新，每个会话独立一条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val keys = remember(revision) { JwtKeyPool.snapshot() }
        if (keys.isEmpty()) {
            Text(
                "暂无活跃 key — 发起一次 agent 对话后出现",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        keys.forEach { (session, iat, minutes) ->
            Text(
                "$session   iat=$iat   ${minutes}min 后换新",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        OutlinedButton(onClick = { JwtKeyPool.dropAll() }) { Text("换新全部 key") }
    }

    @Composable
    private fun ReminderSection() {
        val context = LocalContext.current
        var title by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var time by remember { mutableStateOf("") }
        var important by remember { mutableStateOf(false) }
        var feedback by remember { mutableStateOf("") }

        Text("每日提醒", style = MaterialTheme.typography.titleSmall)
        Text(
            "到点发系统通知；重要任务震动并全屏直达本应用（早报、作业提醒、上课提醒等）。" +
                "也可以直接让 agent 创建",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val items = ReminderStore.all().sortedBy { it.hour * 60 + it.minute }
        if (items.isEmpty()) {
            Text(
                "暂无任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "%02d:%02d  %s".format(r.hour, r.minute, r.title) +
                            if (r.important) "  重要" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        r.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = r.enabled,
                    onCheckedChange = { ReminderStore.setEnabled(context, r.id, it) },
                )
                TextButton(onClick = { ReminderStore.remove(context, r.id) }) { Text("删") }
            }
        }

        Text("新建", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("标题，如：早报") },
            singleLine = true,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("通知内容") },
            singleLine = true,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = time,
                onValueChange = { time = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("时间 HH:mm，如 07:30") },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("重要", style = MaterialTheme.typography.bodySmall)
                Switch(checked = important, onCheckedChange = { important = it })
            }
            Button(
                onClick = {
                    val m = TIME_RE.find(time.trim())
                    val h = m?.groupValues?.get(1)?.toIntOrNull()
                    val min = m?.groupValues?.get(2)?.toIntOrNull()
                    if (title.isBlank() || text.isBlank() || h == null || min == null ||
                        h > 23 || min > 59
                    ) {
                        feedback = "需要标题、内容与合法时间（HH:mm）"
                        return@Button
                    }
                    ReminderStore.add(context, title, text, h, min, important)
                    title = ""
                    text = ""
                    time = ""
                    important = false
                    feedback = "已创建，每日 ${"%02d:%02d".format(h, min)} 提醒"
                },
                enabled = title.isNotBlank() || text.isNotBlank() || time.isNotBlank(),
            ) { Text("添加") }
        }
        if (feedback.isNotEmpty()) {
            Text(feedback, fontSize = 12.sp)
        }
    }

    @Composable
    private fun ExtensionsSection() {
        val context = LocalContext.current
        val activity = context as? FragmentActivity
        val scope = rememberCoroutineScope()
        var jsonInput by remember { mutableStateOf("") }
        var feedback by remember { mutableStateOf("") }
        var skillInput by remember { mutableStateOf("") }
        var skillFeedback by remember { mutableStateOf("") }

        // Master switch for the whole extension interface.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agent 拓展接口", style = MaterialTheme.typography.titleSmall)
                Text(
                    "允许外部 agent 通过 adb 广播增删本应用功能",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ExtensionStore.master.value,
                onCheckedChange = { ExtensionStore.setMaster(it) },
            )
        }

        Text(
            EXT_HINT,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            "已注册拓展（${ExtensionStore.items.value.size}）",
            style = MaterialTheme.typography.titleSmall,
        )
        val list = ExtensionStore.items.value
        if (list.isEmpty()) {
            Text(
                "暂无 — 让 agent 添加，或在下面粘贴 JSON 导入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        list.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium)
                    if (e.desc.isNotBlank()) {
                        Text(
                            e.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = e.enabled,
                    onCheckedChange = { ExtensionStore.setEnabled(e.id, it) },
                )
                TextButton(onClick = { ExtensionStore.remove(e.id) }) { Text("删") }
            }
        }

        OutlinedTextField(
            value = jsonInput,
            onValueChange = { jsonInput = it },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            placeholder = {
                Text(
                    "导入拓展：{\"name\":\"截屏\",\"cmd\":\"screencap -p /sdcard/kami.png\"}",
                    fontSize = 12.sp,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                feedback = try {
                    val n = ExtensionStore.addFromJson(jsonInput)
                    if (n > 0) {
                        jsonInput = ""
                        "已添加 $n 项"
                    } else {
                        "需要 name 与 cmd 字段"
                    }
                } catch (t: Throwable) {
                    "解析失败：${t.message}"
                }
            }) { Text("导入 JSON") }

            // Destructive: gated behind biometric auth when available.
            val fa = activity
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val count = ExtensionStore.items.value.size
                        val ok = if (
                            fa != null && BioGate.available(context) && BioGate.enabled(context)
                        ) {
                            BioGate.authenticate(fa, "清空全部拓展", "将删除 $count 项拓展功能")
                        } else {
                            true
                        }
                        if (ok) ExtensionStore.clear()
                    }
                },
                enabled = ExtensionStore.items.value.isNotEmpty(),
            ) { Text("清空全部") }
        }
        if (feedback.isNotEmpty()) {
            Text(feedback, fontSize = 12.sp)
        }

        Text("技能（提示词模板）", style = MaterialTheme.typography.titleSmall)
        Text(
            SKILL_HINT,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val skills = SkillStore.all()
        if (skills.isEmpty()) {
            Text(
                "暂无技能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        skills.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium)
                    if (s.desc.isNotBlank()) {
                        Text(
                            s.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { SkillStore.remove(s.id) }) { Text("删") }
            }
        }
        OutlinedTextField(
            value = skillInput,
            onValueChange = { skillInput = it },
            modifier = Modifier.fillMaxWidth().height(110.dp),
            placeholder = {
                Text(
                    "导入技能：{\"name\":\"早报\",\"template\":\"生成 {{日期}} 早报\"}",
                    fontSize = 12.sp,
                )
            },
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            skillFeedback = try {
                val n = SkillStore.addFromJson(skillInput)
                if (n > 0) {
                    skillInput = ""
                    "已添加 $n 项技能"
                } else {
                    "需要 name 与 template 字段"
                }
            } catch (t: Throwable) {
                "解析失败：${t.message}"
            }
        }) { Text("导入技能") }
        if (skillFeedback.isNotEmpty()) {
            Text(skillFeedback, fontSize = 12.sp)
        }
    }

    @Composable
    private fun BioSection() {
        val context = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("敏感操作生物验证", style = MaterialTheme.typography.titleSmall)
                val hint = if (BioGate.available(context)) {
                    "agent 危险命令与清空拓展前要求指纹/面部验证"
                } else {
                    "本机未录入生物凭据 — 验证不可用时自动放行"
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = BioGate.enabled(context) && BioGate.available(context),
                onCheckedChange = { BioGate.setEnabled(context, it) },
                enabled = BioGate.available(context),
            )
        }
    }

    @Composable
    private fun PermissionsSection() {
        val context = LocalContext.current
        val revision = rememberResumeRevision()
        var requested by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { requested = true }

        // Only offer permissions actually declared in the manifest —
        // requesting undeclared ones is what crashed the launcher.
        val declared = remember(context) {
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions
                    ?.toSet()
                    .orEmpty()
            }.getOrElse { emptySet() }
        }

        Text("权限管理", style = MaterialTheme.typography.titleSmall)
        Text(
            "按需授权 — agent 会检查权限并提示缺什么",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Re-read grant states on resume (revision) and after a permission
        // dialog returns (requested) — both are read via key() below.
        key(revision, requested) {
            RUNTIME_GROUPS.forEach { group ->
                val present = group.perms.filter { it in declared }
                val missing = present.filter {
                    ContextCompat.checkSelfPermission(context, it) !=
                        PackageManager.PERMISSION_GRANTED
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when {
                        present.isEmpty() -> Text(
                            "不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        missing.isEmpty() -> Text(
                            "已授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        else -> TextButton(onClick = {
                            runCatching { launcher.launch(missing.toTypedArray()) }
                        }) {
                            Text("授权")
                        }
                    }
                }
            }

            Text("特殊访问（系统设置页）", style = MaterialTheme.typography.titleSmall)
            SPECIAL_ACCESS.forEach { sa ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sa.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (sa.granted(context)) {
                        Text(
                            "已授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        TextButton(onClick = { runCatching { sa.open(context) } }) { Text("去开启") }
                    }
                }
            }
        }
    }
}
