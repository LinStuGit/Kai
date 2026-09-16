package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.ime.KamiImeService
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Host-device control for the agent: arbitrary shell commands via Shizuku
 * (shell uid, no root) plus a durable extension registry — the agent turns
 * shell commands into named features and re-runs them by id in later
 * sessions. The sandbox shell tools run inside the Linux container; these
 * drive the Android host itself.
 *
 * The device-control tools ([launchAppTool], [screenshotTool], [screenTouchTool],
 * [inputTextTool]) are sensitive: each passes through [DeviceActionGate] so the
 * user confirms — once, or for the whole session — before anything happens.
 */
@OptIn(ExperimentalUuidApi::class)
object ShizukuTools {

    const val SHELL_ID = "run_shell"
    const val ADD_EXTENSION_ID = "add_extension"
    const val REMOVE_EXTENSION_ID = "remove_extension"
    const val LIST_EXTENSIONS_ID = "list_extensions"
    const val RUN_EXTENSION_ID = "run_extension"
    const val LAUNCH_APP_ID = "launch_app"
    const val SCREENSHOT_ID = "take_screenshot"
    const val SCREEN_TOUCH_ID = "screen_touch"
    const val INPUT_TEXT_ID = "input_text"

    /** IME component id for the headless input method, as `ime set` expects it. */
    private const val KAMI_IME = "com.inspiredandroid.kai/com.inspiredandroid.kai.ime.KamiImeService"

    private fun denied(): Map<String, Any> {
        // Surface the authorization dialog over the chat so the user can fix
        // it in one tap instead of reading the error and hunting for settings.
        ShizukuGate.request()
        return mapOf(
            "success" to false,
            "error" to "Shizuku 未授权——已请求用户授权；授权完成后重试",
        )
    }

    private fun runShell(command: String): String = try {
        ShizukuRunner.run(command).ifEmpty { "(无输出)" }
    } catch (t: Throwable) {
        "shizuku error: $t"
    }

    private fun requireString(args: Map<String, Any>, key: String): String? = args[key] as? String

    /**
     * Sensitive-action gate: bounces the request through the confirmation
     * dialog and maps a rejection onto a tool result the model understands.
     */
    private suspend fun gate(toolName: String, detail: String): Map<String, Any>? {
        val approved = DeviceActionGate.awaitApproval(
            DeviceActionRequest(
                callId = "device-${Uuid.random()}",
                toolName = toolName,
                detail = detail,
            ),
        )
        return if (approved) {
            null
        } else {
            mapOf("success" to false, "error" to "用户拒绝了这次设备操作（${detail.take(120)}）")
        }
    }

    fun shellTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = SHELL_ID,
            description = "在 Android 宿主机上以 shell uid 执行命令（Shizuku，免 root）。" +
                "支持 ; 与 && 串联、管道，可调用 am/pm/settings/dumpsys/getprop/input/screencap 等。" +
                "容器内操作用沙箱的 shell 工具，宿主机操作用这个",
            parameters = mapOf(
                "command" to ParameterSchema("string", "要执行的 shell 命令", true),
            ),
        )
        override val timeout: Duration = 2.minutes
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!ShizukuRunner.granted()) return denied()
            val command = requireString(args, "command")
                ?: return mapOf("success" to false, "error" to "command is required")
            return mapOf("success" to true, "output" to runShell(command))
        }
    }

    fun addExtensionTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = ADD_EXTENSION_ID,
            description = "把 shell 命令注册为命名功能（持久化，跨会话可用，按 id 执行）。" +
                "用户要求新功能时用它固化，而不是只执行一次",
            parameters = mapOf(
                "id" to ParameterSchema("string", "稳定唯一 id，重复则替换；省略则自动生成", false),
                "name" to ParameterSchema("string", "功能名，要短", true),
                "desc" to ParameterSchema("string", "一句话说明", false),
                "cmd" to ParameterSchema("string", "shell 命令", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val name = requireString(args, "name")?.trim().orEmpty()
            val cmd = requireString(args, "cmd")?.trim().orEmpty()
            if (name.isEmpty() || cmd.isEmpty()) {
                return mapOf("success" to false, "error" to "name 与 cmd 不能为空")
            }
            val ext = Extension(
                id = requireString(args, "id")?.trim().orEmpty().ifEmpty { "ext-" + name.hashCode() },
                name = name,
                desc = requireString(args, "desc")?.trim().orEmpty(),
                cmd = cmd,
            )
            ExtensionStore.add(ext)
            return mapOf("success" to true, "id" to ext.id, "message" to "已注册拓展「${ext.name}」")
        }
    }

    fun removeExtensionTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = REMOVE_EXTENSION_ID,
            description = "删除一个已注册的拓展功能",
            parameters = mapOf(
                "id" to ParameterSchema("string", "拓展 id", true),
            ),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val id = requireString(args, "id")
                ?: return mapOf("success" to false, "error" to "id is required")
            val removed = ExtensionStore.remove(id)
            return mapOf(
                "success" to removed,
                "message" to if (removed) "已删除" else "id 不存在",
            )
        }
    }

    fun listExtensionsTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = LIST_EXTENSIONS_ID,
            description = "列出全部已注册的拓展功能（id/name/desc/cmd/enabled）",
            parameters = emptyMap(),
        )
        override suspend fun execute(args: Map<String, Any>): Any {
            val items = ExtensionStore.all()
            return if (items.isEmpty()) {
                mapOf("success" to true, "extensions" to emptyList<Any>(), "message" to "还没有注册任何拓展")
            } else {
                mapOf(
                    "success" to true,
                    "extensions" to items.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "desc" to it.desc,
                            "cmd" to it.cmd,
                            "enabled" to it.enabled,
                        )
                    },
                )
            }
        }
    }

    fun runExtensionTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = RUN_EXTENSION_ID,
            description = "按 id 执行一个已注册的拓展功能",
            parameters = mapOf(
                "id" to ParameterSchema("string", "拓展 id", true),
            ),
        )
        override val timeout: Duration = 2.minutes
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!ShizukuRunner.granted()) return denied()
            val id = requireString(args, "id")
                ?: return mapOf("success" to false, "error" to "id is required")
            val ext = ExtensionStore.get(id)
                ?: return mapOf("success" to false, "error" to "id 不存在")
            if (!ext.enabled) {
                return mapOf("success" to false, "error" to "拓展「${ext.name}」已停用")
            }
            return mapOf("success" to true, "output" to runShell(ext.cmd))
        }
    }

    fun launchAppTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = LAUNCH_APP_ID,
            description = "打开宿主机上的一个应用（按包名）。先用 `pm list packages -3` 查包名再调用",
            parameters = mapOf(
                "package" to ParameterSchema("string", "应用包名，如 com.tencent.mm", true),
            ),
        )
        override val timeout: Duration = 1.minutes
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!ShizukuRunner.granted()) return denied()
            val pkg = requireString(args, "package")?.trim().orEmpty()
            if (pkg.isEmpty()) {
                return mapOf("success" to false, "error" to "package is required")
            }
            gate(LAUNCH_APP_ID, "打开应用 $pkg")?.let { return it }
            val out = runShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            // monkey prints "Events injected: 1" on success; anything else is a failure.
            return if (out.contains("Events injected: 1")) {
                mapOf("success" to true, "message" to "已启动 $pkg")
            } else {
                mapOf("success" to false, "error" to "启动失败：${out.take(300)}")
            }
        }
    }

    fun screenshotTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = SCREENSHOT_ID,
            description = "截取宿主机屏幕，PNG 保存到 Download 目录并返回路径",
            parameters = emptyMap(),
        )
        override val timeout: Duration = 1.minutes
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!ShizukuRunner.granted()) return denied()
            gate(SCREENSHOT_ID, "截取屏幕")?.let { return it }
            val path = "/sdcard/Download/kami-screenshot-${System.currentTimeMillis()}.png"
            val out = runShell("screencap -p $path")
            return if (out.isEmpty()) {
                mapOf("success" to true, "path" to path, "message" to "已保存到 $path")
            } else {
                mapOf("success" to false, "error" to "截图失败：${out.take(300)}")
            }
        }
    }

    fun screenTouchTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = SCREEN_TOUCH_ID,
            description = "在宿主机屏幕上执行触摸手势或按键：tap（点击坐标）、swipe（滑动）、key（按键事件）。" +
                "配合 take_screenshot 观察屏幕后使用",
            parameters = mapOf(
                "action" to ParameterSchema("string", "tap / swipe / key", true),
                "x" to ParameterSchema("int", "tap/swipe 起点 x", false),
                "y" to ParameterSchema("int", "tap/swipe 起点 y", false),
                "x2" to ParameterSchema("int", "swipe 终点 x", false),
                "y2" to ParameterSchema("int", "swipe 终点 y", false),
                "durationMs" to ParameterSchema("int", "swipe 时长毫秒，默认 300", false),
                "keycode" to ParameterSchema("string", "key 动作的按键名或数字，如 KEYCODE_BACK、KEYCODE_HOME、4", false),
            ),
        )
        override val timeout: Duration = 1.minutes
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!ShizukuRunner.granted()) return denied()
            val action = requireString(args, "action")?.trim().lowercase().orEmpty()
            fun num(key: String): Int? = (args[key] as? Number)?.toInt() ?: args[key]?.toString()?.toIntOrNull()
            val cmd = when (action) {
                "tap" -> {
                    val x = num("x")
                    val y = num("y")
                    if (x == null || y == null) return mapOf("success" to false, "error" to "tap 需要 x/y")
                    "input tap $x $y"
                }

                "swipe" -> {
                    val x = num("x")
                    val y = num("y")
                    val x2 = num("x2")
                    val y2 = num("y2")
                    if (x == null || y == null || x2 == null || y2 == null) {
                        return mapOf("success" to false, "error" to "swipe 需要 x/y/x2/y2")
                    }
                    "input swipe $x $y $x2 $y2 ${num("durationMs") ?: 300}"
                }

                "key" -> {
                    val key = requireString(args, "keycode")?.trim().orEmpty()
                    if (key.isEmpty()) return mapOf("success" to false, "error" to "key 需要 keycode")
                    "input keyevent $key"
                }

                else -> return mapOf("success" to false, "error" to "action 必须是 tap/swipe/key")
            }
            val detail = when (action) {
                "tap" -> "点击屏幕 (${num("x")},${num("y")})"
                "swipe" -> "滑动屏幕 (${num("x")},${num("y")})→(${num("x2")},${num("y2")})"
                else -> "按键 ${requireString(args, "keycode")}"
            }
            gate(SCREEN_TOUCH_ID, detail)?.let { return it }
            val out = runShell(cmd)
            return if (out.isEmpty() || !out.contains("Error", ignoreCase = true)) {
                mapOf("success" to true, "message" to "$detail 完成")
            } else {
                mapOf("success" to false, "error" to "${detail}失败：${out.take(300)}")
            }
        }
    }

    fun inputTextTool(): Tool = object : Tool {
        override val schema = ToolSchema(
            name = INPUT_TEXT_ID,
            description = "向宿主机当前聚焦的输入框输入任意文本（支持中文等非 ASCII）。" +
                "流程：临时把输入法切到 Kami 输入法提交文本后切回。目标 app 需已聚焦输入框",
            parameters = mapOf(
                "text" to ParameterSchema("string", "要输入的文本", true),
            ),
        )
        override val timeout: Duration = 2.minutes
        override suspend fun execute(args: Map<String, Any>): Any {
            if (!ShizukuRunner.granted()) return denied()
            val text = requireString(args, "text").orEmpty()
            if (text.isEmpty()) return mapOf("success" to false, "error" to "text is required")
            gate(INPUT_TEXT_ID, "输入文本「${text.take(30)}${if (text.length > 30) "…" else ""}」")?.let { return it }

            val previous = runShell("settings get secure default_input_method").trim()
            runShell("ime enable $KAMI_IME")
            runShell("ime set $KAMI_IME")
            try {
                // Wait for the system to bind our IME to whatever field is focused.
                var waited = 0L
                while (!KamiImeService.bound && waited < 5_000) {
                    delay(100)
                    waited += 100
                }
                if (!KamiImeService.bound) {
                    return mapOf("success" to false, "error" to "Kami 输入法未能在 5 秒内绑定到输入框——请确认目标 app 有聚焦的输入框")
                }
                KamiImeService.pendingCommit = text
                waited = 0
                while (KamiImeService.pendingCommit != null && waited < 3_000) {
                    delay(100)
                    waited += 100
                }
                if (KamiImeService.pendingCommit != null) {
                    KamiImeService.pendingCommit = null
                    return mapOf("success" to false, "error" to "文本提交超时")
                }
                return mapOf("success" to true, "message" to "已输入「${text.take(30)}${if (text.length > 30) "…" else ""}」")
            } finally {
                // Always hand control back to the user's own keyboard.
                if (previous.isNotEmpty() && previous != KAMI_IME) {
                    runShell("ime set $previous")
                }
            }
        }
    }

    // Settings → Tools metadata: plain strings (no res entries); all
    // user-toggleable, so the isToolEnabled preference gating comes free.
    val shellToolInfo = ToolInfo(
        id = SHELL_ID,
        name = "宿主机 Shell（Shizuku）",
        description = "以 shell uid 在 Android 宿主机执行命令（免 root），am/pm/dumpsys 等；容器内操作请用沙箱 shell",
    )
    val addExtensionToolInfo = ToolInfo(
        id = ADD_EXTENSION_ID,
        name = "注册拓展",
        description = "把 shell 命令固化为命名功能，持久化保存并显示在下方拓展列表",
    )
    val removeExtensionToolInfo = ToolInfo(
        id = REMOVE_EXTENSION_ID,
        name = "删除拓展",
        description = "删除一个已注册的拓展功能",
    )
    val listExtensionsToolInfo = ToolInfo(
        id = LIST_EXTENSIONS_ID,
        name = "列出拓展",
        description = "列出全部已注册的拓展功能",
    )
    val runExtensionToolInfo = ToolInfo(
        id = RUN_EXTENSION_ID,
        name = "执行拓展",
        description = "按 id 执行一个已注册的拓展功能，已停用的不可执行",
    )
    val launchAppToolInfo = ToolInfo(
        id = LAUNCH_APP_ID,
        name = "打开应用",
        description = "按包名打开宿主机上的应用，执行前会请求用户确认",
    )
    val screenshotToolInfo = ToolInfo(
        id = SCREENSHOT_ID,
        name = "屏幕截图",
        description = "截取宿主机屏幕并保存到 Download 目录，执行前会请求用户确认",
    )
    val screenTouchToolInfo = ToolInfo(
        id = SCREEN_TOUCH_ID,
        name = "屏幕触控",
        description = "在宿主机屏幕上点击、滑动或按键，执行前会请求用户确认",
    )
    val inputTextToolInfo = ToolInfo(
        id = INPUT_TEXT_ID,
        name = "输入文本",
        description = "通过 Kami 输入法向当前输入框输入任意文本（含中文），执行前会请求用户确认",
    )
}

actual fun getAgentExtensions(): List<AgentExtension> = ExtensionStore.all().map {
    AgentExtension(it.id, it.name, it.desc, it.cmd, it.enabled)
}

actual fun upsertAgentExtensions(extensions: List<AgentExtension>) {
    for (ext in extensions) {
        ExtensionStore.add(Extension(id = ext.id, name = ext.name, desc = ext.desc, cmd = ext.cmd, enabled = ext.enabled))
    }
}

actual fun setAgentExtensionEnabled(id: String, enabled: Boolean) = ExtensionStore.setEnabled(id, enabled)

actual fun removeAgentExtension(id: String) {
    ExtensionStore.remove(id)
}
