package com.kami.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible endpoint settings for the in-app agent. */
data class AgentConfig(val baseUrl: String, val apiKey: String, val model: String)

/**
 * The on-device agent brain: a minimal OpenAI-compatible chat-completions
 * client with a tool-calling loop. No PC bridge involved — the agent talks
 * straight to any configured endpoint (remote API or LAN llama-server) and
 * controls the phone through [ShizukuRunner], persisting features via
 * [ExtensionStore].
 */
object AgentClient {

    private const val SYSTEM_PROMPT =
        "你是 Kami，运行在用户 Android 手机上的助手。通过工具控制这台设备：" +
            "run_shell 以 shell uid 执行命令（Shizuku，免 root，可读 getprop、" +
            "调用 am/pm/settings/dumpsys 等）；add_extension 能把 shell 命令固化成" +
            "用户主界面的一键功能——当用户要求新能力时优先注册拓展，而不是只执行一次。" +
            "回答用中文，简洁直接。"

    private const val MAX_STEPS = 8
    private const val MAX_TOOL_RESULT = 4000

    private val TOOLS: JSONArray = JSONArray(
        """
        [
          {"type":"function","function":{"name":"run_shell","description":"在手机上以 shell uid（Shizuku，免 root）执行命令；支持 ; && 串联与管道","parameters":{"type":"object","properties":{"command":{"type":"string","description":"shell 命令"}},"required":["command"]}}},
          {"type":"function","function":{"name":"add_extension","description":"注册一个新功能：shell 命令固化为用户主界面快捷指令（一键执行）。让功能持久化的正规途径","parameters":{"type":"object","properties":{"id":{"type":"string","description":"可选，稳定唯一 id，重复则替换"},"name":{"type":"string","description":"功能名，要短"},"desc":{"type":"string","description":"一句话说明"},"cmd":{"type":"string","description":"要执行的 shell 命令"}},"required":["name","cmd"]}}},
          {"type":"function","function":{"name":"remove_extension","description":"删除已注册的拓展功能","parameters":{"type":"object","properties":{"id":{"type":"string","description":"拓展 id"}},"required":["id"]}}},
          {"type":"function","function":{"name":"list_extensions","description":"列出全部已注册拓展（JSON 数组）","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"device_info","description":"设备概况：型号/系统版本/无线调试开关与端口","parameters":{"type":"object","properties":{}}}}
        ]
        """.trimIndent(),
    )

    fun loadConfig(context: Context): AgentConfig {
        val p = context.getSharedPreferences("kami_agent", Context.MODE_PRIVATE)
        return AgentConfig(
            baseUrl = p.getString("base", "") ?: "",
            apiKey = p.getString("key", "") ?: "",
            model = p.getString("model", "") ?: "",
        )
    }

    fun saveConfig(context: Context, c: AgentConfig) {
        context.getSharedPreferences("kami_agent", Context.MODE_PRIVATE)
            .edit()
            .putString("base", c.baseUrl)
            .putString("key", c.apiKey)
            .putString("model", c.model)
            .apply()
    }

    /**
     * One user turn: appends to [history] and runs the tool-calling loop
     * until the model answers in prose. [onEvent] reports tool activity for
     * the transcript (called on the caller's thread).
     */
    fun turn(
        config: AgentConfig,
        history: JSONArray,
        userText: String,
        onEvent: (String) -> Unit,
    ): String {
        require(config.model.isNotBlank()) { "未配置模型" }
        history.put(JSONObject().put("role", "user").put("content", userText))
        repeat(MAX_STEPS) {
            val resp = postChat(config, history)
            val msg = resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val toolCalls = msg.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = msg.optString("content")
                history.put(JSONObject().put("role", "assistant").put("content", content))
                return content.ifBlank { "（空回复）" }
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
                    executeTool(name, args)
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
        return "（单轮工具调用已达 $MAX_STEPS 步上限，先回复到此）"
    }

    private fun postChat(config: AgentConfig, history: JSONArray): JSONObject {
        val msgs = JSONArray().put(
            JSONObject().put("role", "system").put("content", SYSTEM_PROMPT),
        )
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", msgs)
            .put("tools", TOOLS)

        val conn = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (config.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(300)}")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun executeTool(name: String, argsJson: String): String {
        val args = try {
            JSONObject(argsJson)
        } catch (t: Throwable) {
            JSONObject()
        }
        return when (name) {
            "run_shell" -> {
                if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权，请用户在主界面授权"
                ShizukuRunner.run(args.optString("command")).ifEmpty { "(无输出)" }
            }
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
}
