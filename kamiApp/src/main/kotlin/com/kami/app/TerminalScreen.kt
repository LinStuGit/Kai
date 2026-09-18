package com.kami.app

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TERM_BANNER =
    "Kami proot 终端 — 命令在 Alpine 沙箱内执行（/data/local/tmp/kami-proot）\n" +
        "apk add 可装软件包；沙箱与手机宿主相互隔离\n"

/**
 * A way into the proot sandbox from the console: each entered line runs
 * inside the Alpine rootfs via [ProotSandbox.run] and its output appends
 * to the transcript. Install pulls proot + minirootfs over Shizuku.
 */
@Composable
internal fun TerminalScreen(onBack: () -> Unit) {
    var transcript by remember { mutableStateOf(TERM_BANNER) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<String>() }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    fun exec(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty() || running) return
        input = ""
        if (history.lastOrNull() != c) history.add(c)
        scope.launch(Dispatchers.IO) {
            running = true
            transcript += "\nalpine$ $c\n"
            transcript += ProotSandbox.run(c).trimEnd() + "\n"
            running = false
        }
    }

    fun install() {
        if (running) return
        scope.launch(Dispatchers.IO) {
            running = true
            transcript += "\n安装沙箱中（下载 proot + Alpine rootfs，约一分钟）…\n"
            transcript += ProotSandbox.setup(prootUrl = null, rootfsUrl = null).trimEnd() + "\n"
            running = false
        }
    }

    LaunchedEffect(transcript) { scroll.scrollTo(scroll.maxValue) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 控制台") }
            Text(
                "proot 终端",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { install() }, enabled = !running) { Text("安装/重装沙箱") }
            if (running) CircularProgressIndicator(Modifier.padding(6.dp))
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
                placeholder = { Text("如: uname -a", fontSize = 13.sp) },
                singleLine = true,
                enabled = !running,
            )
            Button(
                onClick = { exec(input) },
                enabled = !running && input.isNotBlank(),
            ) { Text("执行") }
        }
    }
}
