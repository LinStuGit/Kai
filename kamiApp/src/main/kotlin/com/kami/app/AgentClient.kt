package com.kami.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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

    private const val SYSTEM_PROMPT =
        "你是 Kami，运行在用户 Android 手机上的助手。工具：run_shell 以 shell uid 执行" +
            "命令（Shizuku 免 root，可调用 am/pm/settings/dumpsys）；read_screen 读取屏幕" +
            "控件层级（bounds 给出坐标），screen_touch 点击/滑动/长按/按键，input_text 输入" +
            "文本（需输入框已聚焦），take_screenshot 截图保存给用户（图片对 agent 不可见）；" +
            "sandbox_run 在 Alpine Linux 沙箱内执行（与宿主隔离，apk add 可装包；未安装时先调 " +
            "sandbox_setup，装好后编译/网络/脚本类任务优先在沙箱里做）；add_extension 把 " +
            "shell 命令固化成用户主界面一键功能——当用户要求新能力时优先注册拓展，而不是只执行一次；" +
            "notify_user 可发系统通知（长任务完成或需要用户回来时用）。危险命令（重启/卸载/" +
            "清数据等）会触发生物验证，用户拒绝则立即放弃并说明。回答用中文，简洁直接。"

    private const val MAX_STEPS = 8
    private const val MAX_TOOL_RESULT = 4000

    private val TOOLS: JSONArray = JSONArray(
        """
        [
          {"type":"function","function":{"name":"run_shell","description":"在手机上以 shell uid（Shizuku，免 root）执行命令；支持 ; && 串联与管道","parameters":{"type":"object","properties":{"command":{"type":"string","description":"shell 命令"}},"required":["command"]}}},
          {"type":"function","function":{"name":"sandbox_setup","description":"安装 Alpine Linux proot 沙箱：自动按设备架构下载 proot 静态二进制与 Alpine minirootfs 到 /data/local/tmp/kami-proot。首次使用沙箱前执行一次，重复执行会重装","parameters":{"type":"object","properties":{"proot_url":{"type":"string","description":"可选，自定义 proot 静态二进制下载地址"},"rootfs_url":{"type":"string","description":"可选，自定义 Alpine rootfs tar.gz 地址"}}}}},
          {"type":"function","function":{"name":"sandbox_run","description":"在 Alpine Linux 沙箱内执行命令（与宿主隔离，apk add 可装软件包，适合编译、网络工具、文件处理）","parameters":{"type":"object","properties":{"command":{"type":"string","description":"在 Alpine 内执行的 sh 命令"}},"required":["command"]}}},
          {"type":"function","function":{"name":"sandbox_status","description":"查看沙箱安装状态","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"notify_user","description":"发一条系统通知提醒用户（长任务完成、需要用户回来授权或操作时用）","parameters":{"type":"object","properties":{"title":{"type":"string","description":"通知标题"},"text":{"type":"string","description":"通知正文"}},"required":["text"]}}},
          {"type":"function","function":{"name":"take_screenshot","description":"截取当前屏幕保存到 Download 目录并返回文件路径（图片内容对 agent 不可见；理解屏幕请用 read_screen）","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"read_screen","description":"读取当前屏幕的控件层级（uiautomator 精简 XML，含 text/desc/bounds）。操作屏幕前先读它定位元素；bounds 形如 [x1,y1][x2,y2]","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"screen_touch","description":"操作屏幕：tap 点击、longpress 长按（同坐标按压）、swipe 滑动、key 按键（BACK/HOME/ENTER/DEL/RECENT 等）","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["tap","longpress","swipe","key"],"description":"操作类型"},"x":{"type":"integer","description":"x 坐标"},"y":{"type":"integer","description":"y 坐标"},"x2":{"type":"integer","description":"swipe 终点 x"},"y2":{"type":"integer","description":"swipe 终点 y"},"duration_ms":{"type":"integer","description":"swipe/longpress 时长毫秒"},"key":{"type":"string","description":"key 操作的按键名，如 BACK、HOME"}},"required":["action"]}}},
          {"type":"function","function":{"name":"input_text","description":"在当前聚焦的输入框输入文本：纯 ASCII 直接注入；含中文等字符时自动切换 ADB Keyboard IME 输入再还原","parameters":{"type":"object","properties":{"text":{"type":"string","description":"要输入的文本"}},"required":["text"]}}},
          {"type":"function","function":{"name":"add_extension","description":"注册一个新功能：shell 命令固化为用户主界面快捷指令（一键执行）。让功能持久化的正规途径","parameters":{"type":"object","properties":{"id":{"type":"string","description":"可选，稳定唯一 id，重复则替换"},"name":{"type":"string","description":"功能名，要短"},"desc":{"type":"string","description":"一句话说明"},"cmd":{"type":"string","description":"要执行的 shell 命令"}},"required":["name","cmd"]}}},
          {"type":"function","function":{"name":"remove_extension","description":"删除已注册的拓展功能","parameters":{"type":"object","properties":{"id":{"type":"string","description":"拓展 id"}},"required":["id"]}}},
          {"type":"function","function":{"name":"list_extensions","description":"列出全部已注册拓展（JSON 数组）","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"device_info","description":"设备概况：型号/系统版本/无线调试开关与端口","parameters":{"type":"object","properties":{}}}}
        ]
        """.trimIndent(),
    )

    /**
     * One user turn of session [sessionId]: appends to its [history] and
     * runs the tool-calling loop until the model answers in prose.
     * [onEvent] reports tool activity for the transcript. Suspend so the
     * loop honours cancellation (overlay force-stop) between steps.
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
        repeat(MAX_STEPS) {
            currentCoroutineContext().ensureActive()
            val resp = postChat(sessionId, history)
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
                onEvent("⚙ $name($args)")
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
        return TurnReply(
            "（单轮工具调用已达 $MAX_STEPS 步上限，先回复到此）",
            thinkings.joinToString("\n\n").ifBlank { null },
        )
    }

    /** POST with the session's JWT; 401/403/409 → refetch the token once. */
    private fun postChat(session: String, history: JSONArray): JSONObject {
        val key = JwtKeyPool.acquire("chat:$session")
        val first = send(key, history)
        if (first.code in 200..299) return first.body
        if (first.code !in setOf(401, 403, 409)) {
            throw IOException("HTTP ${first.code}: ${first.text.take(300)}")
        }
        JwtKeyPool.drop("chat:$session")
        val refreshed = send(JwtKeyPool.acquire("chat:$session"), history)
        if (refreshed.code !in 200..299) {
            throw IOException("HTTP ${refreshed.code}: ${refreshed.text.take(300)}")
        }
        return refreshed.body
    }

    private fun send(key: String, history: JSONArray): SendResult {
        val msgs = JSONArray().put(
            JSONObject().put("role", "system").put("content", SYSTEM_PROMPT),
        )
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        val body = JSONObject()
            .put("model", MadModel.MODEL)
            .put("messages", msgs)
            .put("tools", TOOLS)

        val conn = URL(MadModel.BASE.trimEnd('/') + "/chat/completions")
            .openConnection() as HttpURLConnection
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
            conn.disconnect()
        }
    }

    private class SendResult(val code: Int, val text: String, val body: JSONObject)

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
                val before = ExtensionStore.items.value.size
                ExtensionStore.remove(args.optString("id"))
                "已删除 ${before - ExtensionStore.items.value.size} 项"
            }

            "list_extensions" -> ExtensionStore.toJson()

            "device_info" -> {
                if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
                ShizukuRunner.run(
                    "getprop ro.product.model; getprop ro.build.version.release; " +
                        "getprop ro.build.version.sdk; settings get global adb_wifi_enabled; " +
                        "getprop service.adb.tls.port",
                )
            }

            else -> "未知工具: $name"
        }
    }

    private fun notifyUser(context: Context, args: JSONObject): String {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "错误：缺少通知权限，请用户在「设置 → 权限管理」中授予通知权限"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("kami_agent", "Kami Agent", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val n = Notification.Builder(context, "kami_agent")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(args.optString("title").ifBlank { "Kami Agent" })
            .setContentText(args.optString("text"))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() / 1000).toInt(), n)
        return "已发送系统通知"
    }
}
