package com.kami.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import rikka.shizuku.Shizuku

private const val REQ_SHIZUKU = 7001

private const val BANNER =
    "Kami ADB console — 命令经 Shizuku 以 shell uid 执行\n" +
        "支持 ; / && 串联与管道，无 root\n"

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

/**
 * Shizuku-backed ADB shell console: status card, quick actions (built-in +
 * agent-added extensions) and a free command line; Settings manages the
 * extension interface. Every command runs as shell uid via Shizuku — no root.
 */
class MainActivity : ComponentActivity() {

    private val shizukuAlive = mutableStateOf(false)
    private val shizukuGranted = mutableStateOf(false)
    private val screen = mutableStateOf("console")
    private val chatHistory = JSONArray()

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
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        refresh()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen.value) {
                        "settings" -> SettingsScreen()
                        "chat" -> ChatScreen(chatHistory) { screen.value = "console" }
                        else -> ConsoleScreen()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
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
            scope.launch(Dispatchers.IO) {
                running = true
                transcript += "\n→ $c\n"
                val t0 = System.currentTimeMillis()
                val out = ShizukuRunner.run(c)
                val ms = System.currentTimeMillis() - t0
                transcript += if (out.isEmpty()) {
                    "✓ 无输出（${ms}ms）\n"
                } else {
                    "$out\n"
                }
                running = false
            }
        }

        LaunchedEffect(transcript) { scroll.scrollTo(scroll.maxValue) }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kami · Shizuku ADB 控制",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { screen.value = "chat" }) { Text("对话") }
                TextButton(onClick = { screen.value = "settings" }) { Text("设置") }
            }

            val (icon, statusText) = when {
                granted -> "🟢" to "已授权 — 命令将以 shell 身份执行"
                alive -> "🟡" to "Shizuku 在线，等待授权"
                else -> "🔴" to "Shizuku 未运行 — 请先打开 Shizuku APP"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 16.sp)
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

            // Built-ins first, then agent-added extensions (⚙ prefix), both
            // reactive to ExtensionStore changes.
            val extensionActions = if (ExtensionStore.master.value) {
                ExtensionStore.items.value
                    .filter { it.enabled }
                    .map { QuickAction("⚙ ${it.name}", it.cmd) }
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

    @Composable
    private fun SettingsScreen() {
        val scroll = rememberScrollState()
        var jsonInput by remember { mutableStateOf("") }
        var feedback by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { screen.value = "console" }) { Text("← 返回") }
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }

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
                    "暂无 — 让 agent 用上方广播添加，或在下面粘贴 JSON 导入",
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
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = {
                    Text(
                        "手动导入：{\"name\":\"截屏\",\"cmd\":\"screencap -p /sdcard/kami.png\"}",
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
                            "✓ 已添加 $n 项"
                        } else {
                            "⚠️ 需要 name 与 cmd 字段"
                        }
                    } catch (t: Throwable) {
                        "❌ 解析失败：${t.message}"
                    }
                }) { Text("导入 JSON") }
                OutlinedButton(
                    onClick = { ExtensionStore.clear() },
                    enabled = ExtensionStore.items.value.isNotEmpty(),
                ) { Text("清空全部") }
            }
            if (feedback.isNotEmpty()) {
                Text(feedback, fontSize = 12.sp)
            }

            Text("Agent 模型（OpenAI 兼容端点）", style = MaterialTheme.typography.titleSmall)
            val context = LocalContext.current
            val cfg = remember { AgentClient.loadConfig(context) }
            var baseUrl by remember { mutableStateOf(cfg.baseUrl) }
            var apiKey by remember { mutableStateOf(cfg.apiKey) }
            var modelName by remember { mutableStateOf(cfg.model) }
            var savedMsg by remember { mutableStateOf("") }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Base URL，如 https://api.openai.com/v1", fontSize = 12.sp) },
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("API Key（本地服务可留空）", fontSize = 12.sp) },
                singleLine = true,
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("模型名，如 glm-4-flash", fontSize = 12.sp) },
                singleLine = true,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    AgentClient.saveConfig(
                        context,
                        AgentConfig(baseUrl.trim(), apiKey.trim(), modelName.trim()),
                    )
                    savedMsg = "✓ 已保存"
                }) { Text("保存") }
                if (savedMsg.isNotEmpty()) Text(savedMsg, fontSize = 12.sp)
            }
        }
    }
}
