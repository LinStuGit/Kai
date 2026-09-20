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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val SHELL_PROMPT = "shell$ "
private const val ALPINE_PROMPT = "alpine$ "

/** Keep the tty buffer bounded; drop oldest bytes when exceeded. */
private const val TTY_MAX = 40_000

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
private fun commonPrefix(cands: List<String>): String = cands.reduce { a, b -> a.commonPrefixWith(b) }

/** One tty buffer: the whole stream plus the index where the editable
 *  prompt line starts (everything before it is frozen output/history). */
private class Tty(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    var liveStart by mutableStateOf(initial.length)
}

/**
 * The terminal, modelled on a linux tty: ONE text buffer holds the whole
 * session — past output, the prompt and the command being typed live in the
 * same flow with a real cursor, exactly like a console. Typed characters go
 * to the line after the prompt; executed lines and their output freeze into
 * the buffer above (edits there are rejected like on a real terminal);
 * TAB completes, ↑/↓ walk history; Enter runs the line.
 */
@Composable
internal fun TerminalScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf("shell") }
    val shellTty = remember { Tty(SHELL_PROMPT) }
    val alpineTty = remember { Tty(ALPINE_PROMPT) }
    val tty = if (mode == "shell") shellTty else alpineTty
    val prompt = if (mode == "shell") SHELL_PROMPT else ALPINE_PROMPT
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableListOf<Pair<String, String>>() } // mode to cmd
    var histIdx by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    /** The current (editable) input line. */
    fun liveText(): String = tty.value.text.substring(tty.liveStart)

    /** Replace the live line, cursor at its end. */
    fun setLive(newLive: String) {
        val t = tty.value.text.take(tty.liveStart) + newLive
        tty.value = TextFieldValue(t, TextRange(t.length))
    }

    /** Append to the stream end (used while edits are frozen). */
    fun append(s: String) {
        var txt = tty.value.text + s
        var ls = tty.liveStart
        if (txt.length > TTY_MAX) {
            val drop = txt.length - TTY_MAX
            txt = txt.substring(drop)
            ls = (ls - drop).coerceAtLeast(0)
        }
        tty.value = TextFieldValue(txt, TextRange(txt.length))
        tty.liveStart = ls
    }

    /** Print above the prompt line (bash-style completion listings). */
    fun printAbove(s: String) {
        val v = tty.value
        val txt = v.text.substring(0, tty.liveStart) + s + v.text.substring(tty.liveStart)
        val sel = if (v.selection.end >= tty.liveStart) v.selection.end + s.length else v.selection.end
        tty.value = TextFieldValue(txt, TextRange(sel.coerceIn(0, txt.length)))
        tty.liveStart += s.length
    }

    fun exec(raw: String) {
        if (running) return
        val c = raw.trim()
        // The typed line is already on screen — freeze it and print below.
        tty.liveStart = tty.value.text.length
        if (c.isEmpty()) {
            append("\n" + prompt)
            return
        }
        if (history.lastOrNull() != (mode to c)) history.add(mode to c)
        histIdx = -1
        running = true
        scope.launch(Dispatchers.IO) {
            append("\n")
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
            append(prompt)
            tty.liveStart = tty.value.text.length
            running = false
        }
    }

    /** TAB: complete the word at the cursor (command name or path). */
    fun complete() {
        if (running) return
        val text = tty.value.text
        val lineStart = tty.liveStart
        val cur = tty.value.selection.end.coerceIn(lineStart, text.length)
        val before = text.substring(lineStart, cur)
        val after = text.substring(cur)
        val sp = before.lastIndexOf(' ')
        val token = if (sp < 0) before else before.substring(sp + 1)
        if (token.isEmpty()) return
        val tokenStart = cur - token.length

        fun apply(newToken: String, hint: String?) {
            val t = text.substring(0, tokenStart) + newToken + after
            tty.value = TextFieldValue(t, TextRange(tokenStart + newToken.length))
            if (!hint.isNullOrEmpty()) printAbove(hint)
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

    /** Move within the live line only (like a real tty cursor). */
    fun moveCursor(delta: Int) {
        val cur = tty.value.selection.end.coerceIn(tty.liveStart, tty.value.text.length)
        val next = (cur + delta).coerceIn(tty.liveStart, tty.value.text.length)
        tty.value = TextFieldValue(tty.value.text, TextRange(next))
    }

    /** ↑/↓ walk this mode's history. */
    fun historyStep(delta: Int) {
        if (running) return
        val mine = history.mapIndexed { i, (m, cmd) -> if (m == mode) i to cmd else null }
            .filterNotNull()
        if (mine.isEmpty()) return
        val next = (histIdx + delta).coerceIn(-1, mine.size - 1)
        histIdx = next
        setLive(if (next < 0) "" else mine[next].second)
    }

    fun onKey(k: String) {
        when (k) {
            "TAB" -> complete()

            "↑" -> historyStep(-1)

            "↓" -> historyStep(1)

            "←" -> moveCursor(-1)

            "→" -> moveCursor(1)

            else -> {
                if (running) return
                val cur = tty.value.selection.end.coerceIn(tty.liveStart, tty.value.text.length)
                setLive(liveText().let { it.substring(0, (cur - tty.liveStart)) + k + it.substring(cur - tty.liveStart) })
            }
        }
    }

    LaunchedEffect(tty.value.text) { scroll.scrollTo(scroll.maxValue) }

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

        // The tty: one editable buffer — output, prompt and input in the same
        // flow. Editing is only possible after the last prompt, like on a
        // real console; everything above is frozen.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101418), RoundedCornerShape(10.dp))
                .padding(10.dp)
                .verticalScroll(scroll),
        ) {
            BasicTextField(
                value = tty.value,
                onValueChange = { new ->
                    val oldText = tty.value.text
                    val liveStart = tty.liveStart
                    val frozen = oldText.take(liveStart)
                    if (running) return@BasicTextField
                    if (new.text == oldText) {
                        tty.value = new // cursor move / selection only
                        return@BasicTextField
                    }
                    if (new.text.startsWith(frozen) && new.text.length >= liveStart) {
                        val live = new.text.substring(liveStart)
                        // Enter: the LIVE region gained a newline (the frozen
                        // history is full of them — must not match those).
                        if (live.contains('\n')) {
                            exec(live.replace("\n", " ").trim())
                            return@BasicTextField
                        }
                        tty.value = new // normal edit inside the live line
                        return@BasicTextField
                    }
                    // Editing frozen history: redirect insertions to the live
                    // line end (keystrokes always go to the prompt), reject
                    // deletions there (a tty can't unprint).
                    val p = new.text.commonPrefixWith(oldText).length
                    val diff = new.text.length - oldText.length
                    if (diff > 0 && new.text.endsWith(oldText.substring(p))) {
                        setLive(liveText() + new.text.substring(p, p + diff).replace("\n", ""))
                    } else {
                        tty.value = TextFieldValue(oldText, TextRange(oldText.length))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFFD5E0EA),
                ),
                cursorBrush = SolidColor(Color(0xFF9BD1FF)),
            )
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
