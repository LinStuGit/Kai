package com.kami.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One user turn's final output: the prose answer plus accumulated reasoning. */
data class TurnReply(val text: String, val thinking: String?)

/**
 * The on-device agent brain: a minimal OpenAI-compatible chat-completions
 * client with a tool-calling loop, hard-wired to the madmodel endpoint
 * (JWT-as-key, one token per session — see [MadModel] / [JwtKeyPool]).
 * The agent controls the phone through [ShizukuRunner], sees and drives
 * the screen via [ScreenControl], runs isolated commands in [ProotSandbox]
 * and persists features via [ExtensionStore].
 */
object AgentClient {

    /** Built-in system prompt — also the reset baseline for Settings → 系统提示词. */
    const val SYSTEM_PROMPT =
        "你是 Kami，运行在用户 Android 手机上的助手。工具：run_shell 以 shell uid 执行" +
            "命令（Shizuku 免 root，可调用 am/pm/settings/dumpsys）；web_search 联网搜索" +
            "（必应/学校网络可达），web_fetch 抓取网页正文；read_screen 读取屏幕控件层级" +
            "（bounds 给出坐标），screen_touch 点击/滑动/长按/按键，input_text 输入文本" +
            "（需输入框已聚焦），take_screenshot 截图保存给用户（图片对 agent 不可见）；" +
            "sandbox_run 在 Alpine Linux 沙箱内执行（与宿主隔离，apk add 可装包；未安装时先调 " +
            "sandbox_setup，装好后编译/网络/脚本类任务优先在沙箱里做）；add_extension 把 " +
            "shell 命令固化为可复用功能——当用户要求新能力时优先注册拓展，而不是只执行一次；" +
            "notify_user 可发系统通知（长任务完成或需要用户回来时用）。" +
            "跨会话持久记忆可用：用户的偏好、背景、长期约定与重要结论用 memory_save 主动记录" +
            "（不必询问），memory_recall 可检索；add_reminder 创建定时任务，支持四种类型：" +
            "once（指定日期时间）/daily（每日 HH:mm）/weekly（每周几 HH:mm）/interval" +
            "（每 N 分钟），到点发通知，important 时震动并全屏唤醒进应用（早报用 daily、" +
            "作业/上课用 weekly、短暂倒计时用 interval）；list_reminders/remove_reminder 管理；" +
            "calendar_add/calendar_list 读写系统日历日程；list_skills/skill_run 调用用户自定义" +
            "技能模板。屏幕操作要点：先 read_screen 定位（它会收起 Kami 悬浮球面板并收起键盘，" +
            "不会遮挡目标界面），随后凭一次读取的 bounds 连续执行多个点击/输入，不要每步都重读；" +
            "完成后再读一次确认结果。危险操作（重启/卸载/删文件/发短信/打开支付类应用/删提醒等）" +
            "会触发生物验证，用户拒绝则立即放弃并说明。回答用中文，简洁直接。"

    private const val MAX_TOOL_RESULT = 6000

    private val TOOLS: JSONArray = JSONArray(
        """
        [
          {"type":"function","function":{"name":"run_shell","description":"在手机上以 shell uid（Shizuku，免 root）执行命令；支持 ; && 串联与管道","parameters":{"type":"object","properties":{"command":{"type":"string","description":"shell 命令"}},"required":["command"]}}},
          {"type":"function","function":{"name":"web_search","description":"联网搜索（Bing，DuckDuckGo 兜底），返回标题/链接/摘要。查资料、新闻、文档时用","parameters":{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}}},
          {"type":"function","function":{"name":"web_fetch","description":"抓取网页正文文本（去标签，截 4k）。配合 web_search 深入阅读某个结果","parameters":{"type":"object","properties":{"url":{"type":"string","description":"http(s) 地址"}},"required":["url"]}}},
          {"type":"function","function":{"name":"sandbox_setup","description":"安装 Alpine Linux proot 沙箱：proot 与 rootfs 已内置（arm64/arm，免下载，数秒完成）；其它架构或自定义源时用可传 URL 下载。重复执行会重装","parameters":{"type":"object","properties":{"proot_url":{"type":"string","description":"可选，自定义 proot 静态二进制下载地址"},"rootfs_url":{"type":"string","description":"可选，自定义 Alpine rootfs tar.gz 地址"}}}}},
          {"type":"function","function":{"name":"sandbox_run","description":"在 Alpine Linux 沙箱内执行命令（与宿主隔离，apk add 可装软件包，适合编译、网络工具、文件处理）","parameters":{"type":"object","properties":{"command":{"type":"string","description":"在 Alpine 内执行的 sh 命令"}},"required":["command"]}}},
          {"type":"function","function":{"name":"sandbox_status","description":"查看沙箱安装状态","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"notify_user","description":"发一条系统通知提醒用户（长任务完成、需要用户回来授权或操作时用）","parameters":{"type":"object","properties":{"title":{"type":"string","description":"通知标题"},"text":{"type":"string","description":"通知正文"}},"required":["text"]}}},
          {"type":"function","function":{"name":"take_screenshot","description":"截取当前屏幕保存到 Download 目录并返回文件路径（图片内容对 agent 不可见；理解屏幕请用 read_screen）","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"read_screen","description":"读取当前屏幕的控件层级（紧凑树，每行一个节点：类名 \"文本\" (描述) @id，*=可点 ^=可滚 #=选中；坐标=bounds [x1,y1][x2,y2]）。操作屏幕前先读它定位元素","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"screen_touch","description":"操作屏幕：tap 点击、longpress 长按（同坐标按压）、swipe 滑动、key 按键（BACK/HOME/ENTER/DEL/RECENT 等）","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["tap","longpress","swipe","key"],"description":"操作类型"},"x":{"type":"integer","description":"x 坐标"},"y":{"type":"integer","description":"y 坐标"},"x2":{"type":"integer","description":"swipe 终点 x"},"y2":{"type":"integer","description":"swipe 终点 y"},"duration_ms":{"type":"integer","description":"swipe/longpress 时长毫秒"},"key":{"type":"string","description":"key 操作的按键名，如 BACK、HOME"}},"required":["action"]}}},
          {"type":"function","function":{"name":"input_text","description":"在当前聚焦的输入框输入文本：纯 ASCII 直接注入；含中文等字符时自动切换内置 Kami 输入法输入（原输入法稍后自动还原，无需处理）","parameters":{"type":"object","properties":{"text":{"type":"string","description":"要输入的文本"}},"required":["text"]}}},
          {"type":"function","function":{"name":"add_extension","description":"注册一个新功能：shell 命令固化为用户主界面快捷指令（一键执行）。让功能持久化的正规途径","parameters":{"type":"object","properties":{"id":{"type":"string","description":"可选，稳定唯一 id，重复则替换"},"name":{"type":"string","description":"功能名，要短"},"desc":{"type":"string","description":"一句话说明"},"cmd":{"type":"string","description":"要执行的 shell 命令"}},"required":["name","cmd"]}}},
          {"type":"function","function":{"name":"remove_extension","description":"删除已注册的拓展功能","parameters":{"type":"object","properties":{"id":{"type":"string","description":"拓展 id"}},"required":["id"]}}},
          {"type":"function","function":{"name":"list_extensions","description":"列出全部已注册拓展（JSON 数组）","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"device_info","description":"设备概况：型号/系统版本/无线调试开关与端口","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"memory_save","description":"把重要事实写入持久记忆（跨会话有效）：用户偏好、项目背景、长期约定、重要结论。值得记的主动存，不必询问","parameters":{"type":"object","properties":{"text":{"type":"string","description":"要记住的事实，一句话"}},"required":["text"]}}},
          {"type":"function","function":{"name":"memory_recall","description":"检索持久记忆；不带 query 返回最近记忆","parameters":{"type":"object","properties":{"query":{"type":"string","description":"关键词，可省略"}}}}},
          {"type":"function","function":{"name":"add_reminder","description":"创建定时任务，到点发系统通知（important 时震动并全屏唤醒进应用）。四种类型：once 单次（date=2026-09-20）、daily 每日、weekly 每周（weekday 1=周日 2=周一…7=周六）、interval 每 N 分钟。适合早报（daily）、作业/上课（weekly）、稍后提醒（once/interval）","parameters":{"type":"object","properties":{"title":{"type":"string","description":"提醒标题，要短"},"text":{"type":"string","description":"通知内容"},"type":{"type":"string","enum":["once","daily","weekly","interval"],"description":"类型，默认 daily"},"hour":{"type":"integer","description":"小时 0-23（once/daily/weekly 必填）"},"minute":{"type":"integer","description":"分钟 0-59"},"date":{"type":"string","description":"once 的日期，如 2026-09-20"},"weekday":{"type":"integer","description":"weekly 的周几：1=周日 2=周一 … 7=周六"},"interval_min":{"type":"integer","description":"interval 的间隔分钟"},"important":{"type":"boolean","description":"重要：震动+全屏直达，默认 false"}},"required":["title","text"]}}},
          {"type":"function","function":{"name":"remove_reminder","description":"删除定时提醒","parameters":{"type":"object","properties":{"id":{"type":"string","description":"提醒 id 或精确标题"}},"required":["id"]}}},
          {"type":"function","function":{"name":"list_reminders","description":"列出全部定时提醒","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"calendar_add","description":"在系统日历新建日程事件","parameters":{"type":"object","properties":{"title":{"type":"string"},"start_ms":{"type":"integer","description":"开始时间 epoch 毫秒"},"duration_min":{"type":"integer","description":"持续分钟，默认 60"},"desc":{"type":"string","description":"描述，可省略"}},"required":["title","start_ms"]}}},
          {"type":"function","function":{"name":"calendar_list","description":"查看未来几天的系统日历日程","parameters":{"type":"object","properties":{"days":{"type":"integer","description":"往后看几天，默认 7"}}}}},
          {"type":"function","function":{"name":"list_skills","description":"列出可用的技能（用户自定义的提示词模板）","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"skill_run","description":"展开技能模板为完整任务指令，按展开结果立即执行","parameters":{"type":"object","properties":{"name":{"type":"string","description":"技能名"},"args":{"type":"object","description":"模板变量键值对，如 {\"日期\":\"明天\"}"}},"required":["name"]}}}
        ]
        """.trimIndent(),
    )

    /**
     * One user turn of session [sessionId]: appends to its [history] and
     * runs the tool-calling loop with no step cap until the model answers
     * in prose (the only bounds are the per-result size budget and
     * cancellation — the user can force-stop at any time). [onEvent]
     * reports tool activity for the transcript. Suspend so the loop
     * honours cancellation (overlay force-stop) between steps.
     */
    suspend fun turn(
        sessionId: String,
        context: Context,
        history: JSONArray,
        userText: String,
        onEvent: (String) -> Unit,
    ): TurnReply {
        val thinkings = mutableListOf<String>()
        history.put(JSONObject().put("role", "user").put("content", userText))
        while (true) {
            currentCoroutineContext().ensureActive()
            val resp = postChat(sessionId, history, disabledToolNames(context))
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val reasoning = msg.optString("reasoning_content")
            if (reasoning.isNotBlank()) thinkings.add(reasoning.trim())
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = msg.optString("content")
                history.put(JSONObject().put("role", "assistant").put("content", content))
                return TurnReply(
                    content.ifBlank { "（空回复）" },
                    thinkings.joinToString("\n\n").ifBlank { null },
                )
            }
            // Assistant tool-call message must go back verbatim before results.
            history.put(msg)
            for (i in 0 until toolCalls.length()) {
                val tc = toolCalls.getJSONObject(i)
                val fn = tc.getJSONObject("function")
                val name = fn.getString("name")
                val args = fn.optString("arguments").ifBlank { "{}" }
                onEvent("$name($args)")
                val result = try {
                    executeTool(sessionId, context, name, args)
                } catch (t: Throwable) {
                    "工具执行异常：$t"
                }
                onEvent("→ " + result.take(150))
                history.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tc.getString("id"))
                        .put("content", result.take(MAX_TOOL_RESULT)),
                )
            }
        }
    }

    /** POST with the session's JWT; 401/403/409 → refetch the token once. */
    private fun postChat(
        session: String,
        history: JSONArray,
        disabled: Set<String>,
    ): JSONObject {
        val key = JwtKeyPool.acquire("chat:$session")
        val first = send(key, history, disabled)
        if (first.code in 200..299) return first.body
        if (first.code !in setOf(401, 403, 409)) {
            throw IOException("HTTP ${first.code}: ${first.text.take(300)}")
        }
        JwtKeyPool.drop("chat:$session")
        val refreshed = send(JwtKeyPool.acquire("chat:$session"), history, disabled)
        if (refreshed.code !in 200..299) {
            throw IOException("HTTP ${refreshed.code}: ${refreshed.text.take(300)}")
        }
        return refreshed.body
    }

    private fun send(key: String, history: JSONArray, disabled: Set<String>): SendResult {
        val msgs = JSONArray().put(
            JSONObject().put("role", "system").put("content", systemContent()),
        )
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        val body = JSONObject()
            .put("model", MadModel.MODEL)
            .put("messages", msgs)
            .put("tools", toolSchemas(disabled))

        val conn = URL(MadModel.BASE.trimEnd('/') + "/chat/completions")
            .openConnection() as HttpURLConnection
        liveConns.add(conn)
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            val payload = if (code in 200..299) JSONObject(text) else JSONObject()
            return SendResult(code, text, payload)
        } finally {
            liveConns.remove(conn)
            conn.disconnect()
        }
    }

    private class SendResult(val code: Int, val text: String, val body: JSONObject)

    /** Chat requests currently on the wire, for hard abort. */
    private val liveConns = java.util.concurrent.CopyOnWriteArrayList<HttpURLConnection>()

    /**
     * Force-stop hook: disconnect every in-flight chat request so cancelled
     * jobs stop waiting on the socket read instead of blocking until the
     * 120s read timeout.
     */
    fun abortAll() {
        liveConns.toList().forEach { runCatching { it.disconnect() } }
    }

    /** System prompt (user-editable, see Settings → 系统提示词) plus the
     *  latest persisted memories for this turn. */
    private fun systemContent(): String {
        val mem = MemoryStore.recent(30)
        val base = SystemPromptStore.get()
        return if (mem.isEmpty()) {
            base
        } else {
            base + "\n\n[持久记忆]\n" + mem.joinToString("\n") { "- $it" }
        }
    }

    /** name + description of every built-in tool, for the management UI. */
    val BUILTIN_INFO: List<Pair<String, String>> by lazy {
        (0 until TOOLS.length()).map { i ->
            val f = TOOLS.getJSONObject(i).getJSONObject("function")
            f.getString("name") to f.getString("description")
        }
    }

    private fun toolPrefs(context: Context) = context.getSharedPreferences("kami_tools", Context.MODE_PRIVATE)

    fun isToolDisabled(name: String, context: Context): Boolean = toolPrefs(context).getBoolean(name, false)

    fun setToolDisabled(context: Context, name: String, disabled: Boolean) {
        toolPrefs(context).edit().putBoolean(name, disabled).apply()
    }

    private fun disabledToolNames(context: Context): Set<String> = BUILTIN_INFO.map { it.first }.filter { isToolDisabled(it, context) }.toSet()

    /** Built-in tools (minus user-disabled ones) merged with the registry (MCP). */
    private fun toolSchemas(disabled: Set<String>): JSONArray {
        val all = JSONArray()
        for (i in 0 until TOOLS.length()) {
            val t = TOOLS.getJSONObject(i)
            val name = t.getJSONObject("function").getString("name")
            if (name !in disabled) all.put(t)
        }
        val extra = ExtraToolRegistry.schemas()
        for (i in 0 until extra.length()) all.put(extra.getJSONObject(i))
        return all
    }

    private fun executeTool(
        sessionId: String,
        context: Context,
        name: String,
        argsJson: String,
    ): String {
        val args = try {
            JSONObject(argsJson)
        } catch (t: Throwable) {
            JSONObject()
        }
        if (BUILTIN_INFO.any { it.first == name } && isToolDisabled(name, context)) {
            return "该内置能力已被用户在设置中停用：$name（告知用户后改用其他方式）"
        }
        return when (name) {
            "run_shell" -> {
                if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权，请用户在主界面授权"
                val cmd = args.optString("command")
                if (SensitiveGate.isDangerous(cmd)) {
                    val allowed = runBlocking {
                        SensitiveGate.await("敏感命令", cmd.take(120))
                    }
                    if (!allowed) return "用户拒绝执行该敏感命令（或生物验证失败）——不要重试，改问用户"
                }
                ShizukuRunner.run(cmd).ifEmpty { "(无输出)" }
            }

            "sandbox_setup" -> ProotSandbox.setup(
                prootUrl = args.optString("proot_url").takeIf { it.isNotBlank() },
                rootfsUrl = args.optString("rootfs_url").takeIf { it.isNotBlank() },
            )

            "sandbox_run" -> ProotSandbox.run(args.optString("command"))

            "sandbox_status" -> ProotSandbox.status()

            "notify_user" -> notifyUser(context, args)

            "take_screenshot" -> ScreenControl.screenshot()

            "read_screen" -> ScreenControl.readScreen()

            "screen_touch" -> ScreenControl.touch(
                action = args.optString("action"),
                x = args.optInt("x"),
                y = args.optInt("y"),
                x2 = args.optInt("x2", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                y2 = args.optInt("y2", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                durationMs = args.optInt("duration_ms", 0).takeIf { it > 0 },
                key = args.optString("key").takeIf { it.isNotBlank() },
            )

            "input_text" -> ScreenControl.inputText(args.optString("text"))

            "add_extension" -> {
                val n = ExtensionStore.addFromJson(args.toString())
                "已注册 $n 项拓展（现共 ${ExtensionStore.items.value.size} 项）"
            }

            "remove_extension" -> {
                val allowed = runBlocking {
                    SensitiveGate.await("删除拓展", args.optString("id").take(120))
                }
                if (!allowed) return "用户拒绝删除（或生物验证失败）——不要重试"
                val before = ExtensionStore.items.value.size
                ExtensionStore.remove(args.optString("id"))
                "已删除 ${before - ExtensionStore.items.value.size} 项"
            }

            "list_extensions" -> ExtensionStore.toJson()

            "memory_save" -> {
                if (MemoryStore.save(args.optString("text"))) {
                    "已记住（现共 ${MemoryStore.all().size} 条）"
                } else {
                    "未保存（内容为空或已存在）"
                }
            }

            "memory_recall" -> {
                val q = args.optString("query").trim()
                val found = if (q.isEmpty()) MemoryStore.recent(20) else MemoryStore.search(q)
                if (found.isEmpty()) "（无匹配记忆）" else found.joinToString("\n")
            }

            "web_search" -> WebSearch.search(args.optString("query"))

            "web_fetch" -> WebSearch.fetch(args.optString("url"))

            "add_reminder" -> {
                val type = args.optString("type").ifBlank { "daily" }
                val hour = args.optInt("hour").coerceIn(0, 23)
                val minute = args.optInt("minute").coerceIn(0, 59)
                val r = when (type) {
                    "once" -> {
                        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(args.optString("date"))
                        if (m == null) {
                            return "错误：once 类型需要 date（格式 2026-09-20）"
                        }
                        Reminder(
                            id = newReminderId(), title = args.optString("title").trim(),
                            text = args.optString("text").trim(),
                            important = args.optBoolean("important"), enabled = true,
                            type = "once", hour = hour, minute = minute,
                            year = m.groupValues[1].toInt(),
                            month = m.groupValues[2].toInt(),
                            day = m.groupValues[3].toInt(),
                        )
                    }

                    "weekly" -> {
                        val wd = args.optInt("weekday")
                        if (wd < 1 || wd > 7) return "错误：weekly 类型需要 weekday（1=周日 … 7=周六）"
                        Reminder(
                            id = newReminderId(), title = args.optString("title").trim(),
                            text = args.optString("text").trim(),
                            important = args.optBoolean("important"), enabled = true,
                            type = "weekly", hour = hour, minute = minute, weekday = wd,
                        )
                    }

                    "interval" -> {
                        val iv = args.optInt("interval_min")
                        if (iv < 1) return "错误：interval 类型需要 interval_min（>=1 分钟）"
                        Reminder(
                            id = newReminderId(),
                            title = args.optString("title").trim(),
                            text = args.optString("text").trim(),
                            important = args.optBoolean("important"),
                            enabled = true,
                            type = "interval",
                            intervalMin = iv,
                        )
                    }

                    else -> Reminder(
                        id = newReminderId(),
                        title = args.optString("title").trim(),
                        text = args.optString("text").trim(),
                        important = args.optBoolean("important"),
                        enabled = true,
                        type = "daily",
                        hour = hour,
                        minute = minute,
                    )
                }
                ReminderStore.add(context.applicationContext, r)
                val nextCal = ReminderScheduler.nextAt(r)
                val next = nextCal?.let {
                    "%04d-%02d-%02d %02d:%02d".format(
                        it.get(java.util.Calendar.YEAR),
                        it.get(java.util.Calendar.MONTH) + 1,
                        it.get(java.util.Calendar.DAY_OF_MONTH),
                        it.get(java.util.Calendar.HOUR_OF_DAY),
                        it.get(java.util.Calendar.MINUTE),
                    )
                } ?: "（已过期，不会触发）"
                "已创建定时任务：${ReminderScheduler.describe(r)}「${r.title}」" +
                    (if (r.important) "（重要：震动+全屏直达）" else "") + "，下次触发 $next"
            }

            "remove_reminder" -> {
                val id = args.optString("id")
                val target = ReminderStore.get(id)?.title ?: id
                val allowed = runBlocking {
                    SensitiveGate.await("删除定时任务", target.take(120))
                }
                if (!allowed) return "用户拒绝删除（或生物验证失败）——不要重试"
                val removed = ReminderStore.remove(context.applicationContext, id)
                if (removed) "已删除定时任务" else "未找到该提醒（list_reminders 可查 id）"
            }

            "list_reminders" -> {
                val all = ReminderStore.all()
                if (all.isEmpty()) {
                    "（暂无定时任务）"
                } else {
                    all.joinToString("\n") {
                        "%s id=%s %s%s%s".format(
                            it.title,
                            it.id,
                            ReminderScheduler.describe(it),
                            if (it.important) " [重要]" else "",
                            if (!it.enabled) " [已停用]" else "",
                        )
                    }
                }
            }

            "calendar_add" -> CalendarTools.addEvent(
                context,
                args.optString("title"),
                args.optLong("start_ms"),
                args.optInt("duration_min", 60),
                args.optString("desc"),
            )

            "calendar_list" -> CalendarTools.listEvents(context, args.optInt("days", 7))

            "list_skills" -> {
                val skills = SkillStore.all()
                if (skills.isEmpty()) {
                    "（暂无技能 — 用户可在设置页导入，或把建议的模板给用户）"
                } else {
                    skills.joinToString("\n") { s ->
                        "${s.name}（${s.desc.ifBlank { "无描述" }}）模板：${s.template.take(200)}"
                    }
                }
            }

            "skill_run" -> {
                val skill = SkillStore.byName(args.optString("name"))
                if (skill == null) {
                    "未知技能：${args.optString("name")}（list_skills 可查）"
                } else {
                    val expanded = SkillStore.fill(skill, args.optJSONObject("args") ?: JSONObject())
                    "技能「${skill.name}」已展开，请按以下指令立即执行任务：\n$expanded"
                }
            }

            "device_info" -> {
                if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
                ShizukuRunner.run(
                    "getprop ro.product.model; getprop ro.build.version.release; " +
                        "getprop ro.build.version.sdk; settings get global adb_wifi_enabled; " +
                        "getprop service.adb.tls.port",
                )
            }

            else -> ExtraToolRegistry.get(name)?.execute(argsJson) ?: "未知工具: $name"
        }
    }

    private fun newReminderId(): String = "rem-" + System.currentTimeMillis()

    private fun notifyUser(context: Context, args: JSONObject): String {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "错误：缺少通知权限，请用户在「设置 → 权限管理」中授予通知权限"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            // IMPORTANCE_HIGH: heads-up banner, shade, and lockscreen /
            // 焦点通知（灵动岛类）都能完整展示。
            nm.createNotificationChannel(
                NotificationChannel("kami_agent", "Kami Agent", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val n = Notification.Builder(context, "kami_agent")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(args.optString("title").ifBlank { "Kami Agent" })
            .setContentText(args.optString("text"))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() / 1000).toInt(), n)
        return "已发送系统通知"
    }
}
