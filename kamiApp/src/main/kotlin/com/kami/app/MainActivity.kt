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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/**
 * Shizuku-backed ADB shell console: status card, quick actions and a free
 * command line. Every command runs as shell uid via Shizuku — no root.
 */
class MainActivity : ComponentActivity() {

    private val shizukuAlive = mutableStateOf(false)
    private val shizukuGranted = mutableStateOf(false)

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
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        refresh()
        setContent { ConsoleScreen() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }

    @Composable
    private fun ConsoleScreen() {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
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
                    Text("Kami · Shizuku ADB 控制", style = MaterialTheme.typography.titleLarge)

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

                    Text("快捷指令", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QUICK_ACTIONS.forEach { qa ->
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
        }
    }
}
