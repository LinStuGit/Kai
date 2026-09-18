package com.kami.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val SHELL_PROMPT = "shell$ "
private const val ALPINE_PROMPT = "alpine$ "

private val EXTRA_KEYS = listOf(
    "TAB", "/", "|", "\\", "\"", "'", ":", ";", "~", "$", "-",
    "_", ">", "<", "!", "@", "#", "%", "&", "*", "(", ")", "[", "]", "=", "^",
)

private val KEY_INSERT = mapOf("TAB" to "\t")

/**
 * The terminal, termux-style: one screen, two targets — the host shell
 * (Shizuku, shell uid) and the Alpine sandbox (proot). Free text selection
 * everywhere, an extra-keys row above the input, arrow keys through
 * history. No quick-action chips.
 */
@Composable
internal fun TerminalScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf("shell") }
    var shellOut by remember { mutableStateOf(SHELL_PROMPT) }
    var alpineOut by remember { mutableStateOf(ALPINE_PROMPT) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<Pair<String, String>>() } // mode to cmd
    var histIdx by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val transcript = if (mode == "shell") shellOut else alpineOut
    val prompt = if (mode == "shell") SHELL_PROMPT else ALPINE_PROMPT

    fun append(text: String) {
        if (mode == "shell") shellOut += text else alpineOut += text
    }

    fun exec(raw: String) {
        val c = raw.trim()
        if (c.isEmpty() || running) return
        if (history.lastOrNull() != (mode to c)) history.add(mode to c)
        histIdx = -1
        input = TextFieldValue("")
        scope.launch(Dispatchers.IO) {
            running = true
            append("\n$prompt$c\n")
            val out = if (mode == "shell") {
                if (ShizukuRunner.granted()) {
                    ShizukuRunner.run(c).ifEmpty { "（无输出）" }
                } else {
                    "错误：Shizuku 未授权 — 回主页完成授权"
                }
            } else {
                ProotSandbox.run(c)
            }
            append(out.trimEnd() + "\n")
            running = false
        }
    }

    fun installSandbox() {
        if (running) return
        scope.launch(Dispatchers.IO) {
            running = true
            append("\n安装沙箱中（内置资源，数秒）…\n")
            append(ProotSandbox.setup(null, null).trimEnd() + "\n")
            running = false
        }
    }

    fun insertKey(s: String) {
        val sel = input.selection
        val start = sel.min.coerceIn(0, input.text.length)
        val end = sel.max.coerceIn(0, input.text.length)
        input = TextFieldValue(
            input.text.substring(0, start) + s + input.text.substring(end),
            selection = TextRange(start + s.length),
        )
    }

    /** ↑/↓ walk this mode's history. */
    fun historyStep(delta: Int) {
        val mine = history.mapIndexed { i, (m, cmd) -> if (m == mode) i to cmd else null }
            .filterNotNull()
        if (mine.isEmpty()) return
        val next = (histIdx + delta).coerceIn(-1, mine.size - 1)
        histIdx = next
        val text = if (next < 0) "" else mine[next].second
        input = TextFieldValue(text, selection = TextRange(text.length))
    }

    LaunchedEffect(transcript) { scroll.scrollTo(scroll.maxValue) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 主页") }
            Text(
                "终端",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = { mode = "shell" }) {
                Text(if (mode == "shell") "• Shell" else "Shell")
            }
            TextButton(onClick = { mode = "alpine" }) {
                Text(if (mode == "alpine") "• Alpine" else "Alpine")
            }
        }

        if (mode == "alpine") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "命令在 Alpine 沙箱内执行（apk add 可装包）",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (running) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 6.dp))
                } else {
                    TextButton(onClick = { installSandbox() }) { Text("安装/重装") }
                }
            }
        }

        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Text(
                transcript,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101418), RoundedCornerShape(10.dp))
                    .verticalScroll(scroll)
                    .padding(10.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFD5E0EA),
            )
        }

        // Extra keys row (termux-style), plus ↑/↓ history.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("↑", "↓").forEach { k ->
                Text(
                    k,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { historyStep(if (k == "↑") -1 else 1) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                )
            }
            EXTRA_KEYS.forEach { k ->
                Text(
                    k,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { insertKey(KEY_INSERT[k] ?: k) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
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
                placeholder = { Text(prompt + "ls -la", fontSize = 13.sp) },
                singleLine = true,
                enabled = !running,
            )
            Button(
                onClick = { exec(input.text) },
                enabled = !running && input.text.isNotBlank(),
            ) { Text("执行") }
        }
    }
}
