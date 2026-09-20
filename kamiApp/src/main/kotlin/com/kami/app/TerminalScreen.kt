package com.kami.app

import android.os.ParcelFileDescriptor
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import moe.shizuku.server.IRemoteProcess
import java.io.FileInputStream
import java.io.FileOutputStream

/** Keep the tty buffer bounded; drop oldest bytes when exceeded. */
private const val TTY_MAX = 40_000

/**
 * The single-buffer tty state: the whole session text plus the index where
 * the live (editable) region after the last prompt starts.
 */
internal class Tty(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    var liveStart by mutableStateOf(initial.length)
}

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

/**
 * A single persistent shell process behind a terminal session. Commands are
 * fed to its stdin and each is followed by a marker probe; the reader thread
 * accumulates output until the marker shows up, which yields the command's
 * output, its exit code and the live cwd — a REAL continuous session, so
 * `cd`, env vars and everything else persist naturally.
 */
internal class LiveProc(argv: Array<String>, firstInput: String) {

    private val proc: IRemoteProcess = ShizukuRunner.spawn(argv)
    private val outPfd = proc.inputStream
    private val inPfd = proc.outputStream
    private val stdin = FileOutputStream(inPfd.fileDescriptor)
    private val pending = StringBuilder()

    /** Set by the reader thread when the process closes its stdout. */
    @Volatile
    var dead = false
        private set

    private var nonce = 0

    init {
        // Merge stderr into stdout so interleaved error text lands in order.
        Thread {
            try {
                FileInputStream(outPfd.fileDescriptor).reader().use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(pending) { pending.append(buf, 0, n) }
                    }
                }
            } catch (_: Throwable) {
            }
            dead = true
        }.apply { isDaemon = true }.start()
        if (firstInput.isNotEmpty()) write(firstInput)
    }

    fun alive(): Boolean = !dead && try {
        proc.alive()
    } catch (t: Throwable) {
        false
    }

    fun destroy() {
        try {
            proc.destroy()
        } catch (_: Throwable) {
        }
    }

    private fun write(s: String) {
        stdin.write(s.toByteArray())
        stdin.flush()
    }

    sealed class Result {
        data class Ok(val display: String, val code: String, val pwd: String) : Result()
        object Dead : Result()
    }

    /**
     * Run [cmd] in this shell and wait for the marker. The pending buffer is
     * drained of everything the shell printed for this command; output the
     * process produces later (background jobs) stays there for the next one.
     */
    fun exec(cmd: String): Result {
        if (dead) return Result.Dead
        val n = ++nonce
        val marker = "__KAMI_END_${n}_"
        synchronized(pending) { pending.setLength(0) }
        write(
            cmd + "\n" +
                "printf '$marker%s@%s@' \"\$?\" \"\$(pwd)\"\n",
        )
        while (alive()) {
            var res: Result? = null
            synchronized(pending) {
                val i = pending.indexOf(marker)
                if (i >= 0) {
                    val rest = pending.substring(i + marker.length)
                    val c1 = rest.indexOf('@')
                    val c2 = if (c1 >= 0) rest.indexOf('@', c1 + 1) else -1
                    if (c1 >= 0 && c2 > c1) {
                        val display = pending.substring(0, i)
                        val leftover = pending.substring(c2 + 1)
                        pending.setLength(0)
                        pending.append(leftover)
                        res = Result.Ok(display, rest.substring(0, c1), rest.substring(c1 + 1, c2))
                    }
                }
            }
            res?.let { return it }
            Thread.sleep(40)
        }
        return Result.Dead
    }
}

/** Per-mode state of a terminal session: tty buffer, process, history. */
internal class TermMode(initialPrompt: String) {
    val tty = Tty(initialPrompt)
    var running by mutableStateOf(false)
    var cwd by mutableStateOf("/")
    val history = mutableListOf<String>()
    var histIdx = -1
    var proc: LiveProc? = null
    var firstInputSent = false
}

/** One terminal session — named, with independent shell/alpine consoles. */
internal class TermSession(val id: Int) {
    var name by mutableStateOf("终端 $id")
    val shell = TermMode("shell:/$ ")
    val alpine = TermMode("alpine:/$ ")
}

/** Process-global session registry: sessions survive navigation, like chat. */
internal object TerminalHub {
    val sessions = mutableStateListOf<TermSession>()
    private var nextId = 1

    fun create(): TermSession = TermSession(nextId++).also { sessions.add(it) }

    fun remove(s: TermSession) {
        sessions.remove(s)
        s.shell.proc?.destroy()
        s.alpine.proc?.destroy()
    }

    fun destroyAll() {
        sessions.toList().forEach { remove(it) }
    }
}

/** The current (editable) input line of a tty buffer. */
private fun liveText(t: Tty): String = t.value.text.substring(t.liveStart)

private fun setLive(t: Tty, newLive: String) {
    val txt = t.value.text.take(t.liveStart) + newLive
    t.value = TextFieldValue(txt, TextRange(txt.length))
}

/** Append to the stream end (frozen region grows, live line stays last). */
private fun appendTty(t: Tty, s: String) {
    var txt = t.value.text + s
    var ls = t.liveStart
    if (txt.length > TTY_MAX) {
        val drop = txt.length - TTY_MAX
        txt = txt.substring(drop)
        ls = (ls - drop).coerceAtLeast(0)
    }
    t.value = TextFieldValue(txt, TextRange(txt.length))
    t.liveStart = ls
}

/** Print above the prompt line (bash-style completion listings). */
private fun printAbove(t: Tty, s: String) {
    val v = t.value
    val txt = v.text.substring(0, t.liveStart) + s + v.text.substring(t.liveStart)
    val sel = if (v.selection.end >= t.liveStart) v.selection.end + s.length else v.selection.end
    t.value = TextFieldValue(txt, TextRange(sel.coerceIn(0, txt.length)))
    t.liveStart += s.length
}

/**
 * The terminal: multiple named sessions, each a REAL persistent shell
 * process (Shizuku) — the host shell, or one long-lived proot for Alpine —
 * so `cd` and state persist like a real tty. One text buffer per mode holds
 * the whole flow; editing is only possible after the last prompt; TAB
 * completes, ↑/↓ walk history, Enter runs the line.
 */
@Composable
internal fun TerminalScreen(onBack: () -> Unit) {
    if (TerminalHub.sessions.isEmpty()) TerminalHub.create()
    var activeIdx by remember { mutableStateOf(0) }
    var sessionsOpen by remember { mutableStateOf(false) }
    val session = TerminalHub.sessions[activeIdx.coerceIn(0, TerminalHub.sessions.size - 1)]

    var mode by remember { mutableStateOf("shell") }
    val modeT = if (mode == "shell") session.shell else session.alpine
    val tty = modeT.tty
    val running = modeT.running
    val scroll = rememberScrollState()

    fun prompt(): String = (if (mode == "shell") "shell" else "alpine") + ":" + modeT.cwd.trimEnd('/').ifEmpty { "/" } + "$ "

    /** Spawn (or reuse) the persistent process for this mode. */
    fun procFor(): LiveProc? {
        modeT.proc?.takeIf { it.alive() }?.let { return it }
        modeT.proc?.destroy()
        modeT.proc = null
        val p = try {
            if (mode == "shell") {
                LiveProc(arrayOf("sh", "-c", "exec sh 2>&1"), "")
            } else {
                val cmd = ProotSandbox.persistentShellCmd() ?: return null
                LiveProc(arrayOf("sh", "-c", cmd), "export PATH=/bin:/sbin:/usr/bin:/usr/sbin\n")
            }
        } catch (t: Throwable) {
            null
        }
        modeT.proc = p
        modeT.firstInputSent = mode != "alpine"
        return p
    }

    /** Send one command, wait for the marker; never touches the tty. */
    fun execOnProc(cmd: String): LiveProc.Result? {
        val p = procFor() ?: return null
        return p.exec(cmd)
    }

    fun exec(raw: String) {
        if (running) return
        val c = raw.trim()
        // The typed line is already on screen — freeze it and print below.
        tty.liveStart = tty.value.text.length
        if (c.isEmpty()) {
            appendTty(tty, "\n" + prompt())
            return
        }
        if (modeT.history.lastOrNull() != c) modeT.history.add(c)
        modeT.histIdx = -1
        modeT.running = true
        Thread {
            if (c == "clear") {
                modeT.tty.value = TextFieldValue("", TextRange(0))
                appendTty(modeT.tty, prompt())
                modeT.running = false
                return@Thread
            }
            appendTty(tty, "\n")
            when (val r = execOnProc(c)) {
                null -> appendTty(tty, "错误：会话进程启动失败（检查 Shizuku 授权；沙箱需先安装）\n")

                is LiveProc.Result.Dead -> appendTty(tty, "（会话已退出 — 输入任意命令将重新启动）\n" + prompt())

                is LiveProc.Result.Ok -> {
                    val shown = r.display.trimEnd('\n')
                    if (shown.isNotBlank()) appendTty(tty, shown + "\n")
                    if (r.pwd.isNotBlank()) modeT.cwd = r.pwd
                    appendTty(tty, prompt())
                }
            }
            modeT.running = false
        }.apply { isDaemon = true }.start()
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
        val tokenStart = cur - token.length

        fun apply(newToken: String, hint: String?) {
            val t = text.substring(0, tokenStart) + newToken + after
            tty.value = TextFieldValue(t, TextRange(tokenStart + newToken.length))
            if (!hint.isNullOrEmpty()) printAbove(tty, hint)
        }

        Thread {
            if (sp < 0) {
                val cands = (
                    COMMANDS + modeT.history.map { it.trim().substringBefore(' ') }
                    )
                    .filter { it.startsWith(token) && token.isNotEmpty() }
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
                if (token.startsWith("-")) return@Thread
                val slash = token.lastIndexOf('/')
                val dir = if (slash < 0) "" else token.substring(0, slash + 1)
                val prefix = token.substring(slash + 1)
                // The listing runs inside the SAME persistent shell — so it
                // resolves relative paths against the live cwd, like bash.
                // Empty token: list the cwd itself.
                val probe = if (prefix.isEmpty() && dir.isEmpty()) {
                    "ls -A 2>/dev/null | head -60"
                } else {
                    "ls -d ${shellQuote(dir)}${shellQuote(prefix)}* 2>/dev/null | head -60"
                }
                val r = execOnProc(probe) ?: return@Thread
                val out = (r as? LiveProc.Result.Ok)?.display ?: return@Thread
                val cands = out.lines()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() && !it.contains("__KAMI_END_") }
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
        }.apply { isDaemon = true }.start()
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
        if (modeT.history.isEmpty()) return
        val next = (modeT.histIdx + delta).coerceIn(-1, modeT.history.size - 1)
        modeT.histIdx = next
        setLive(tty, if (next < 0) "" else modeT.history[next])
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
                setLive(
                    tty,
                    liveText(tty).let { it.substring(0, (cur - tty.liveStart)) + k + it.substring(cur - tty.liveStart) },
                )
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

        // Session selector: collapsed chip + expandable list, like the chat.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { sessionsOpen = !sessionsOpen }) {
                Text(
                    if (sessionsOpen) {
                        "▼ 会话(${TerminalHub.sessions.size})"
                    } else {
                        "▸ ${session.name}"
                    },
                )
            }
            TextButton(onClick = {
                TerminalHub.remove(session)
                if (TerminalHub.sessions.isEmpty()) TerminalHub.create()
                activeIdx = activeIdx.coerceIn(0, TerminalHub.sessions.size - 1)
            }) { Text("✕ 关闭") }
        }
        if (sessionsOpen) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TerminalHub.sessions.forEachIndexed { i, s ->
                    Text(
                        (if (i == activeIdx) "• " else "") + s.name,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .clickable { activeIdx = i }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                    )
                }
                OutlinedButton(onClick = { TerminalHub.create() }) { Text("＋ 新会话") }
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
            SelectionContainer {
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
                            setLive(tty, liveText(tty) + new.text.substring(p, p + diff).replace("\n", ""))
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
