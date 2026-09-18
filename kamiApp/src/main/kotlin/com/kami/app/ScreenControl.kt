package com.kami.app

/**
 * Agent-facing host screen control, all through Shizuku shell:
 * screenshot to Download, uiautomator hierarchy dump (the agent's "eyes"),
 * tap/swipe/longpress/key, and text input — ASCII straight through
 * `input text`, everything else via the bundled ADB-Keyboard IME.
 */
object ScreenControl {

    private val ASCII_ONLY = Regex("^[A-Za-z0-9@#%+=:;._\\-/]+$")
    private val KEY_NAME = Regex("^[A-Z0-9_]+$")

    fun screenshot(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        val path = "/sdcard/Download/kami-${System.currentTimeMillis()}.png"
        val out = ShizukuRunner.run("screencap -p $path && ls -l $path")
        if (!out.contains("kami-")) return "错误：截屏失败：$out"
        return "已保存 $path（图片内容对 agent 不可见；理解屏幕内容请用 read_screen）"
    }

    /**
     * Trimmed uiautomator dump: drop noisy attributes, keep text / ids /
     * descs / clickable / bounds so it fits the tool-result budget.
     */
    fun readScreen(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        val xml = ShizukuRunner.run(
            "uiautomator dump /sdcard/kami-uidump.xml >/dev/null 2>&1; " +
                "sed -E 's/(index|package|checkable|checked|enabled|focusable|focused|" +
                "selected|password|NAF)=\"[^\"]*\" //g' /sdcard/kami-uidump.xml 2>/dev/null | " +
                "head -c 8000; rm -f /sdcard/kami-uidump.xml",
        )
        return xml.ifBlank {
            "错误：屏幕层级获取失败（屏幕可能非空闲或目标窗口禁止 dump，稍后重试）"
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
    ): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        val cmd = when (action) {
            "tap" -> "input tap $x $y"

            "longpress" -> "input swipe $x $y $x $y ${durationMs ?: 600}"

            "swipe" -> "input swipe $x $y ${x2 ?: x} ${y2 ?: y} ${durationMs ?: 300}"

            "key" -> {
                val name = (key ?: "").trim().uppercase()
                if (!KEY_NAME.matches(name)) return "错误：非法按键名：$key"
                "input keyevent KEYCODE_$name"
            }

            else -> return "错误：未知 action：$action"
        }
        return ShizukuRunner.run(cmd).ifEmpty { "$action 完成" }
    }

    fun inputText(text: String): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        if (text.isBlank()) return "错误：空文本"

        // Pure ASCII: no IME dance needed, `input text` carries it directly.
        if (ASCII_ONLY.matches(text)) {
            val out = ShizukuRunner.run("input text '$text'")
            return out.ifEmpty { "已输入（input text）" }
        }

        // Non-ASCII (e.g. Chinese): switch to the bundled ADB-Keyboard IME,
        // broadcast the text, then restore the previous IME.
        val ime = "com.kami.app/.AdbKeyboardService"
        val original = ShizukuRunner.run("settings get secure default_input_method").trim()
        ShizukuRunner.run("ime enable $ime")
        ShizukuRunner.run("ime set $ime")
        try {
            Thread.sleep(400) // give the IME time to bind
            val esc = text.replace("'", "'\\''")
            ShizukuRunner.run("am broadcast -a ADB_INPUT_TEXT --es msg '$esc'")
            Thread.sleep(400) // give the receiver time to commit
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            if (original.isNotEmpty() && !original.contains("null") && original != ime) {
                ShizukuRunner.run("ime set $original")
            }
        }
        return "已发送输入（若未生效，目标界面可能没有聚焦的输入框）"
    }
}
