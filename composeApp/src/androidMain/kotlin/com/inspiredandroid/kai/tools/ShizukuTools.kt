package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Host-device control for the agent: arbitrary shell commands via Shizuku
 * (shell uid, no root) plus a durable extension registry — the agent turns
 * shell commands into named features and re-runs them by id in later
 * sessions. The sandbox shell tools run inside the Linux container; these
 * drive the Android host itself.
 */
object ShizukuTools {

    const val SHELL_ID = "run_shell"
    const val ADD_EXTENSION_ID = "add_extension"
    const val REMOVE_EXTENSION_ID = "remove_extension"
    const val LIST_EXTENSIONS_ID = "list_extensions"
    const val RUN_EXTENSION_ID = "run_extension"

    private fun denied(): Map<String, Any> = mapOf(
        "success" to false,
        "error" to "Shizuku 未授权——请用户在 Shizuku 管理器中给本应用授权后重试",
    )

    private fun runShell(command: String): String = try {
        ShizukuRunner.run(command).ifEmpty { "(无输出)" }
    } catch (t: Throwable) {
        "shizuku error: $t"
    }

    private fun requireString(args: Map<String, Any>, key: String): String? = args[key] as? String

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
}
