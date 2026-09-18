package com.kami.app

import android.content.Context
import android.os.PowerManager

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

    /**
     * Run [block] with the screen on: a SCREEN_BRIGHT wake lock that also
     * wakes a sleeping display, released as soon as the call returns
     * (30s timeout guards against a lost release). Inline so the lambdas
     * may return out of the whole tool call.
     */
    private inline fun <T> withScreenOn(block: () -> T): T {
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
            "已保存 $path（图片内容对 agent 不可见；理解屏幕内容请用 read_screen）"
        }
    }

    /**
     * Trimmed uiautomator dump: drop noisy attributes, keep text / ids /
     * descs / clickable / bounds so it fits the tool-result budget. Our
     * overlay is hidden and a showing keyboard is dismissed first — the
     * first BACK only closes the IME.
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
                "uiautomator dump /sdcard/kami-uidump.xml >/dev/null 2>&1; " +
                    "sed -E 's/(index|package|checkable|checked|enabled|focusable|focused|" +
                    "selected|password|NAF)=\"[^\"]*\" //g' /sdcard/kami-uidump.xml 2>/dev/null | " +
                    "head -c 12000; rm -f /sdcard/kami-uidump.xml",
            )
            xml.ifBlank {
                "错误：屏幕层级获取失败（屏幕可能非空闲或目标窗口禁止 dump，稍后重试）"
            }
        }
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
        val ime = "com.kami.app/.AdbKeyboardService"
        val original = ShizukuRunner.run("settings get secure default_input_method").trim()
        ShizukuRunner.run("ime enable $ime")
        ShizukuRunner.run("settings put secure default_input_method $ime")
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
            if (original.isNotEmpty() && original != "null" && original != ime) {
                ShizukuRunner.run("settings put secure default_input_method $original")
            }
        }
        if (committed) {
            "已输入（ADB Keyboard 确认提交，第 $attempts 次广播）"
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
