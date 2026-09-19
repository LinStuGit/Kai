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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val SHELL_PROMPT = "shell$ "
private const val ALPINE_PROMPT = "alpine$ "

private val EXTRA_KEYS = listOf(
    "TAB", "↑", "↓", "←", "→", "/", "|", "\\", "\"", "'", ":",
    ";", "~", "$", "-", "_", ">", "<", "!", "@", "#", "%", "&",
    "*", "(", ")", "[", "]", "=", "^",
)

/** First-word TAB completion pool (host shell + sandbox share most of it). */
private val COMMANDS = listOf(
    "ls", "cd", "pwd", "cat", "echo", "grep", "head", "tail", "wc", "touch",
    "mkdir", "rm", "cp", "mv", "chmod", "chown", "ln", "tar", "gzip", "gunzip",
    "find", "which", "whoami", "id", "date", "uname", "uptime", "top", "ps",
    "df", "free", "du", "netstat", "ping", "curl", "wget", "sh", "vi", "sed",
    "awk", "sort", "uniq", "kill", "pgrep", "pkill", "md5sum", "base64",
    "printf", "test", "sleep", "clear", "exit",
    "logcat", "dumpsys", "settings", "getprop", "setprop", "pm", "am", "cmd",
    "svc", "input", "screencap", "screenrecord", "uiautomator", "content",
    "appops", "device_config", "wm", "bugreport", "ip", "ifconfig",
    "proot", "busybox", "apk", "python3",
)

private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** Longest common prefix of a non-empty list. */
private fun commonPrefix(cands: List<String>): String =
    cands.reduce { a, b -> a.commonPrefixWith(b) }

/**
 * The terminal, termux-style: one dark TTY block where output and the
 * prompt line live together (no separate input box) — send with the IME
 * action. TAB completes command names and paths, arrows move through
 * history and the cursor, everything on screen is selectable.
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
                    ShizukuRunner.run(c)
                } else {
                    "错误：Shizuku 未授权 — 回主页完成授权"
                }
            } else {
                ProotSandbox.run(c)
            }
            // Keep the device's own output verbatim; only mark true silence.
            append(if (out.isBlank()) "（无输出）\n" else out + "\n")
            running = false
        }
    }

    /** TAB: complete the word at the cursor (command name or path). */
    fun complete() {
        if (running) return
        val text = input.text
        val cur = input.selection.end.coerceIn(0, text.length)
        val before = text.substring(0, cur)
        val after = text.substring(cur)
        val sp = before.lastIndexOf(' ')
        val token = if (sp < 0) before else before.substring(sp + 1)
        if (token.isEmpty()) return
        val tokenStart = cur - token.length

        fun apply(newToken: String, hint: String?) {
            input = TextFieldValue(
                before.substring(0, tokenStart) + newToken + after,
                selection = TextRange(tokenStart + newToken.length),
            )
            if (!hint.isNullOrEmpty()) append(hint)
        }

        scope.launch(Dispatchers.IO) {
            if (sp < 0) {
                val cands = (
                    COMMANDS + history.filter { it.first == mode }
                        .map { it.second.trim().substringBefore(' ') }
                    )
                    .filter { it.startsWith(token) }
                    .distinct()
                    .sorted()
                when {
                    cands.isEmpty() -> Unit
                    cands.size == 1 -> apply(cands[0] + " ", null)
                    else -> {
                        val cp = commonPrefix(cands)
                        apply(
                            if (cp.length > token.length) cp else token,
                            "\n" + cands.joinToString("  ") + "\n",
                        )
                    }
                }
            } else {
                if (token.startsWith("-")) return@launch
                val slash = token.lastIndexOf('/')
                val dir = if (slash < 0) "" else token.substring(0, slash + 1)
                val prefix = token.substring(slash + 1)
                val cmd = "ls -d ${shellQuote(dir)}${shellQuote(prefix)}* 2>/dev/null | head -60"
                val out = if (mode == "shell") ShizukuRunner.run(cmd) else ProotSandbox.run(cmd)
                val cands = out.lines()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() && !it.startsWith("错误") && !it.startsWith("shizuku") }
                when {
                    cands.isEmpty() -> Unit
                    cands.size == 1 -> apply(cands[0], null)
                    else -> {
                        val cp = commonPrefix(cands)
                        apply(
                            if (cp.length > token.length) cp else token,
                            "\n" + cands.joinToString("  ") + "\n",
                        )
                    }
                }
            }
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

    fun moveCursor(delta: Int) {
        val cur = input.selection.end.coerceIn(0, input.text.length)
        val next = (cur + delta).coerceIn(0, input.text.length)
        input = TextFieldValue(input.text, selection = TextRange(next))
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

    fun onKey(k: String) {
        when (k) {
            "TAB" -> complete()
            "↑" -> historyStep(-1)
            "↓" -> historyStep(1)
            "←" -> moveCursor(-1)
            "→" -> moveCursor(1)
            else -> insertKey(k)
        }
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
            if (running) {
                CircularProgressIndicator(Modifier.padding(horizontal = 6.dp))
            }
            TextButton(onClick = { mode = "shell" }) {
                Text(if (mode == "shell") "• Shell" else "Shell")
            }
            TextButton(onClick = { mode = "alpine" }) {
                Text(if (mode == "alpine") "• Alpine" else "Alpine")
            }
        }

        // One TTY block: transcript and the prompt line live in the same box.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101418), RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Text(
                    transcript,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFD5E0EA),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prompt,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF9BD1FF),
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !running,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFD5E0EA),
                    ),
                    cursorBrush = SolidColor(Color(0xFF9BD1FF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { exec(input.text) }),
                )
            }
        }

        // Extra keys row (termux-style): TAB completes, arrows navigate.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EXTRA_KEYS.forEach { k ->
                Text(
                    k,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { onKey(k) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
