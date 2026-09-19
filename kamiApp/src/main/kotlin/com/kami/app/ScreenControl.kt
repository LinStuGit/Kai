package com.kami.app

import android.content.Context
import android.os.PowerManager
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Agent-facing host screen control, all through Shizuku shell:
 * screenshot to Download, uiautomator hierarchy dump (the agent's "eyes"),
 * tap/swipe/longpress/key, and text input — ASCII straight through
 * `input text`, everything else via the bundled ADB-Keyboard IME.
 *
 * Every call holds a bright wake lock while it runs: dumps and synthetic
 * taps hit nothing on a sleeping display, so the screen is lit (and woken
 * if asleep) for the duration of the operation. Screen reads also hide the
 * Kami overlay and dismiss a showing keyboard so neither occludes what
 * the agent is about to reason over.
 */
object ScreenControl {

    private val ASCII_ONLY = Regex("^[A-Za-z0-9@#%+=:;._\\-/]+$")
    private val KEY_NAME = Regex("^[A-Z0-9_]+$")

    private var app: Context? = null
    private const val WAKE_TIMEOUT_MS = 30_000L

    /** Call once from Activity startup with an application context. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    private const val IME_ID = "com.kami.app/.AdbKeyboardService"

    /** Previous default IME while ours is temporarily the default. */
    @Volatile
    private var pendingIme: String? = null

    @Volatile
    private var pendingImeAt: Long = 0L

    /**
     * Put the user's previous IME back. Deferred on purpose: restoring
     * right after input re-binds their keyboard, which pops it over the
     * screen so later taps land on the keyboard. Restores when the run
     * ends / the app resumes / after a long gap, and collapses the
     * keyboard if the switch brought one up.
     */
    fun restoreImeIfNeeded(force: Boolean = false) {
        val orig = pendingIme ?: return
        if (!force && System.currentTimeMillis() - pendingImeAt < 60_000) return
        synchronized(this) {
            val o = pendingIme ?: return
            pendingIme = null
            if (o.isNotEmpty() && o != "null" && o != IME_ID && ShizukuRunner.granted()) {
                ShizukuRunner.run("settings put secure default_input_method $o")
            }
            if (ShizukuRunner.run("dumpsys input_method")
                    .lineSequence()
                    .any { it.contains("mInputShown=true") }
            ) {
                ShizukuRunner.run("input keyevent 4") // BACK: first press only dismisses the IME
            }
        }
    }

    /**
     * Run [block] with the screen on: a SCREEN_BRIGHT wake lock that also
     * wakes a sleeping display, released as soon as the call returns
     * (30s timeout guards against a lost release). Inline so the lambdas
     * may return out of the whole tool call.
     */
    private inline fun <T> withScreenOn(block: () -> T): T {
        restoreImeIfNeeded()
        AgentOverlayState.screenBusy.value += 1
        var lock: PowerManager.WakeLock? = null
        try {
            app?.let { ctx ->
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                lock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "kami:screen-control",
                )
                lock?.acquire(WAKE_TIMEOUT_MS)
            }
            return block()
        } finally {
            lock?.release()
            AgentOverlayState.screenBusy.value -= 1
        }
    }

    /** Hide our own overlay window for the duration of [block]. */
    private inline fun <T> withOverlayHidden(block: () -> T): T {
        val was = AgentOverlayState.suppress.value
        AgentOverlayState.suppress.value = true
        return try {
            block()
        } finally {
            AgentOverlayState.suppress.value = was
        }
    }

    fun screenshot(): String = withScreenOn {
        withOverlayHidden {
            if (!ShizukuRunner.granted()) return@withScreenOn "错误：Shizuku 未授权"
            val path = "/sdcard/Download/kami-${System.currentTimeMillis()}.png"
            val out = ShizukuRunner.run("screencap -p $path && ls -l $path")
            if (!out.contains("kami-")) return@withScreenOn "错误：截屏失败：$out"
            // Shots exist for the user to open, but they pile up — keep only
            // the newest five.
            ShizukuRunner.run(
                "ls -t /sdcard/Download/kami-*.png 2>/dev/null | tail -n +6 | xargs -r rm -f",
            )
            "已保存 $path（图片内容对 agent 不可见；理解屏幕内容请用 read_screen）"
        }
    }

    /**
     * The agent's "eyes": uiautomator dump re-rendered as a compact
     * one-node-per-line tree — indented plain lines run a fraction of the
     * raw XML's size, cutting both the tool-result budget and how much the
     * model has to read before it can act. The dump goes to a hidden temp
     * file that is removed in the same shell roundtrip (older versions'
     * leftover in /sdcard is swept too); screenshots aside, no screen
     * operation leaves intermediate files behind.
     */
    fun readScreen(): String = withScreenOn {
        withOverlayHidden {
            if (!ShizukuRunner.granted()) return@withScreenOn "错误：Shizuku 未授权"
            val imeShown = ShizukuRunner.run("dumpsys input_method | grep mInputShown")
                .contains("mInputShown=true")
            if (imeShown) {
                ShizukuRunner.run("input keyevent KEYCODE_BACK")
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val xml = ShizukuRunner.run(
                "F=/data/local/tmp/.kami-ui.xml; rm -f \$F /sdcard/kami-uidump.xml; " +
                    "uiautomator dump --compressed \$F >/dev/null 2>&1; " +
                    "[ -s \$F ] || uiautomator dump \$F >/dev/null 2>&1; " +
                    "cat \$F 2>/dev/null; rm -f \$F",
            )
            if (xml.isBlank()) {
                return@withScreenOn "错误：屏幕层级获取失败（屏幕可能非空闲或目标窗口禁止 dump，稍后重试）"
            }
            "屏幕控件树（类名 \"文本\" (描述) @id，*=可点 ^=可滚 #=选中；坐标 [x1,y1][x2,y2]）\n" +
                renderUiTree(xml)
        }
    }

    /** One line per dump node; null = pure container, skip but keep children. */
    private fun uiLine(p: XmlPullParser, depth: Int): String? {
        fun a(name: String): String = p.getAttributeValue(null, name) ?: ""
        val text = a("text").replace(Regex("\\s+"), " ").replace("\"", "'").trim()
        val desc = a("content-desc").replace(Regex("\\s+"), " ").replace("\"", "'").trim()
        val rawId = a("resource-id")
        val rid = if (rawId.contains("/id/")) "@" + rawId.substringAfterLast("/id/") else ""
        val clickable = a("clickable") == "true"
        val scrollable = a("scrollable") == "true"
        val bounds = a("bounds")
        if (text.isEmpty() && desc.isEmpty() && rid.isEmpty() && !clickable && !scrollable) {
            return null
        }
        val sb = StringBuilder("  ".repeat(depth))
        sb.append(a("class").substringAfterLast('.').ifEmpty { "View" })
        if (text.isNotEmpty()) sb.append(" \"").append(text.take(80)).append('"')
        if (desc.isNotEmpty()) sb.append(" (").append(desc.take(60)).append(')')
        if (rid.isNotEmpty()) sb.append(' ').append(rid)
        if (clickable) sb.append(" *")
        if (scrollable) sb.append(" ^")
        if (a("selected") == "true") sb.append(" #")
        if (bounds.isNotEmpty()) sb.append(' ').append(bounds)
        return sb.toString()
    }

    /**
     * Flatten the uiautomator XML into compact indented lines — same
     * bounds the model taps by, a fraction of the characters. Pure-wrapper
     * nodes vanish; if parsing fails the raw XML is shown as fallback.
     */
    private fun renderUiTree(xml: String, maxChars: Int = 12_000): String {
        val sb = StringBuilder()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var depth = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT && sb.length <= maxChars) {
                when (ev) {
                    XmlPullParser.START_TAG -> if (p.name == "node") {
                        uiLine(p, depth.coerceAtMost(24))?.let { sb.append(it).append('\n') }
                        depth++
                    }

                    XmlPullParser.END_TAG -> if (p.name == "node") {
                        depth = (depth - 1).coerceAtLeast(0)
                    }
                }
                ev = p.next()
            }
        } catch (_: Throwable) {
            // Malformed/truncated dump: keep whatever parsed.
        }
        if (sb.isBlank()) return xml.take(maxChars)
        if (sb.length > maxChars) sb.append("…（已截断）\n")
        return sb.toString()
    }

    fun touch(
        action: String,
        x: Int,
        y: Int,
        x2: Int?,
        y2: Int?,
        durationMs: Int?,
        key: String?,
    ): String = withScreenOn {
        if (!ShizukuRunner.granted()) return@withScreenOn "错误：Shizuku 未授权"
        val cmd = when (action) {
            "tap" -> "input tap $x $y"

            "longpress" -> "input swipe $x $y $x $y ${durationMs ?: 600}"

            "swipe" -> "input swipe $x $y ${x2 ?: x} ${y2 ?: y} ${durationMs ?: 300}"

            "key" -> {
                val name = (key ?: "").trim().uppercase()
                if (!KEY_NAME.matches(name)) return@withScreenOn "错误：非法按键名：$key"
                "input keyevent KEYCODE_$name"
            }

            else -> return@withScreenOn "错误：未知 action：$action"
        }
        ShizukuRunner.run(cmd).ifEmpty { "$action 完成" }
    }

    fun inputText(text: String): String = withScreenOn {
        if (!ShizukuRunner.granted()) return@withScreenOn "错误：Shizuku 未授权"
        if (text.isBlank()) return@withScreenOn "错误：空文本"

        // Pure ASCII: no IME dance needed, `input text` carries it directly.
        if (ASCII_ONLY.matches(text)) {
            val out = ShizukuRunner.run("input text '$text'")
            return@withScreenOn out.ifEmpty { "已输入（input text）" }
        }

        // Non-ASCII (e.g. Chinese): make the bundled ADB keyboard the
        // default IME via a direct secure-settings write (more reliable
        // than `ime set`, which needs an active input client on some
        // ROMs), broadcast until the IME confirms the commit, restore.
        val ime = IME_ID
        val original = ShizukuRunner.run("settings get secure default_input_method").trim()
        synchronized(this) {
            if (pendingIme == null) {
                pendingIme = original
                pendingImeAt = System.currentTimeMillis()
            }
        }
        ShizukuRunner.run("ime enable $ime")
        ShizukuRunner.run("settings put secure default_input_method $ime")
        // On ROMs with a live input client, `ime set` switches immediately too.
        ShizukuRunner.run("ime set $ime")
        val seq0 = AdbKeyboardService.commitCount()
        var committed = false
        var attempts = 0
        try {
            Thread.sleep(400) // give the IME time to bind after the switch
            val esc = text.replace("'", "'\\''")
            while (!committed && attempts < 3) {
                attempts++
                ShizukuRunner.run("am broadcast -a ADB_INPUT_TEXT --es msg '$esc'")
                committed = awaitCommit(seq0, 4000)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pendingImeAt = System.currentTimeMillis() // restore later — see restoreImeIfNeeded
        }
        if (committed) {
            "已输入（Kami 输入法确认提交，第 $attempts 次广播）"
        } else {
            "已广播输入但未收到提交确认（目标界面可能没有聚焦的输入框，请先聚焦输入框再试）"
        }
    }

    /** Poll [AdbKeyboardService.commitCount] until it moves past [seq0]. */
    private fun awaitCommit(seq0: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AdbKeyboardService.commitCount() > seq0) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }
}
