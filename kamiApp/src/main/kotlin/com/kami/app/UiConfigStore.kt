package com.kami.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Config-file-driven UI: `kami_ui.json` in the app's filesDir describes an
 * optional theme (hex colors) and any number of custom sub pages rendered as
 * native widgets. Every read re-checks the file's mtime, so edits made by the
 * settings editor or the agent's `ui_config` tool take effect immediately —
 * the agent can add/remove pages or recolor the app just by rewriting this
 * file. Schema (all fields optional):
 *
 * ```json
 * {
 *   "theme": {"primary":"#7C4DFF","onPrimary":"","background":"",
 *             "onBackground":"","surface":"","onSurface":"","surfaceVariant":""},
 *   "home": [{"type":"header","text":"主页自定义区"},
 *            {"type":"link","text":"文档","url":"https://…"}],
 *   "hidden": ["set-perms"],
 *   "pages": [{"id":"demo","title":"示例页","widgets":[
 *       {"type":"header","text":"标题"},
 *       {"type":"text","text":"正文"},
 *       {"type":"link","text":"打开网页","url":"https://…"},
 *       {"type":"button","text":"去设置","target":"settings","toast":"已点"},
 *       {"type":"switch","text":"开关","key":"demo","default":false}]}]
 * }
 * ```
 *
 * `home` renders the same widget list on the chat home (below the session
 * bar); `hidden` hides built-in settings entries (by route, e.g.
 * "set-perms") or custom pages (by id).
 */
object UiConfigStore {

    data class Widget(
        val type: String,
        val text: String = "",
        val url: String = "",
        val target: String = "",
        val toast: String = "",
        val key: String = "",
        val default: Boolean = false,
    )

    data class Page(
        val id: String,
        val title: String,
        val widgets: List<Widget>,
    )

    data class Theme(
        val primary: String = "",
        val onPrimary: String = "",
        val background: String = "",
        val onBackground: String = "",
        val surface: String = "",
        val onSurface: String = "",
        val surfaceVariant: String = "",
    )

    data class Config(
        val theme: Theme? = null,
        /** ""/follow = follow the system; "dark"/"light" force one. */
        val darkTheme: String = "",
        val home: List<Widget> = emptyList(),
        val hidden: List<String> = emptyList(),
        val pages: List<Page> = emptyList(),
    )

    private const val FILE = "kami_ui.json"
    private const val SWITCH_PREFS = "kami_ui_switch"

    /** Returned by [raw] until the user/agent saves a config. */
    const val DEFAULT_SAMPLE =
        "{\n" +
            "  \"theme\": {\"primary\": \"\", \"onPrimary\": \"\", \"background\": \"\", \"onBackground\": \"\", \"surface\": \"\", \"onSurface\": \"\", \"surfaceVariant\": \"\"},\n" +
            "  \"darkTheme\": \"\",\n" +
            "  \"home\": [],\n" +
            "  \"hidden\": [],\n" +
            "  \"pages\": [\n" +
            "    {\"id\": \"demo\", \"title\": \"示例页\", \"widgets\": [\n" +
            "      {\"type\": \"header\", \"text\": \"由配置驱动的子页\"},\n" +
            "      {\"type\": \"text\", \"text\": \"agent 通过 ui_config 工具或设置页编辑本 JSON，保存后界面即时生效\"},\n" +
            "      {\"type\": \"link\", \"text\": \"打开 example.com\", \"url\": \"https://example.com\"},\n" +
            "      {\"type\": \"button\", \"text\": \"回设置\", \"target\": \"settings\"},\n" +
            "      {\"type\": \"switch\", \"text\": \"示例开关\", \"key\": \"demo\", \"default\": false}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}"

    private lateinit var appContext: Context
    private var cached: Config? = null
    private var cachedMtime = -1L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun file(): File = File(appContext.filesDir, FILE)

    /** File mtime as a cheap change signal for hot reload; 0 when unset. */
    fun mtime(): Long = if (::appContext.isInitialized && file().exists()) file().lastModified() else 0L

    /** Parsed config (mtime-cached; re-reads the file on change). */
    fun get(): Config {
        if (!::appContext.isInitialized) return Config()
        val f = file()
        if (!f.exists()) return Config()
        val m = f.lastModified()
        if (m != cachedMtime || cached == null) {
            cached = try {
                parse(f.readText())
            } catch (t: Throwable) {
                Config()
            }
            cachedMtime = m
        }
        return cached ?: Config()
    }

    /** Raw JSON text; the default sample when nothing was saved yet. */
    fun raw(): String = if (!::appContext.isInitialized || !file().exists()) {
        DEFAULT_SAMPLE
    } else {
        file().readText()
    }

    /** Validate + save; returns a user-facing result message. */
    fun setJson(text: String): String {
        if (!::appContext.isInitialized) return "错误：未初始化"
        return try {
            val cfg = parse(text)
            require(cfg.pages.size <= 50) { "子页过多（>50）" }
            file().writeText(text)
            cachedMtime = -1L
            "已保存：${cfg.pages.size} 个子页 · 主页 ${cfg.home.size} 个控件" +
                if (cfg.theme != null) " · 主题色" else ""
        } catch (t: Throwable) {
            "错误：JSON 无效 — ${t.message}"
        }
    }

    /** Night mode: "" follows the system; "dark"/"light" force one. */
    fun setDarkTheme(value: String): String {
        if (!::appContext.isInitialized) return "错误：未初始化"
        return try {
            val root = JSONObject(raw())
            root.put("darkTheme", value)
            parse(root.toString())
            file().writeText(root.toString())
            cachedMtime = -1L
            when (value) {
                "dark" -> "已切换：深色"
                "light" -> "已切换：浅色"
                else -> "已切换：跟随系统"
            }
        } catch (t: Throwable) {
            "错误：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    fun reset(): String {
        if (!::appContext.isInitialized) return "错误：未初始化"
        file().delete()
        cachedMtime = -1L
        return "已恢复默认界面"
    }

    fun switchOn(key: String, default: Boolean): Boolean = appContext.getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE).getBoolean(key, default)

    fun setSwitch(key: String, on: Boolean) {
        appContext.getSharedPreferences(SWITCH_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, on).apply()
    }

    private fun parse(text: String): Config {
        val root = JSONObject(text)
        val theme = root.optJSONObject("theme")?.let { t ->
            Theme(
                primary = t.optString("primary"),
                onPrimary = t.optString("onPrimary"),
                background = t.optString("background"),
                onBackground = t.optString("onBackground"),
                surface = t.optString("surface"),
                onSurface = t.optString("onSurface"),
                surfaceVariant = t.optString("surfaceVariant"),
            )
        }
        fun widgets(arr: JSONArray?): List<Widget> = arr?.let { wa ->
            (0 until wa.length()).map { j ->
                val w = wa.getJSONObject(j)
                Widget(
                    type = w.optString("type", "text"),
                    text = w.optString("text"),
                    url = w.optString("url"),
                    target = w.optString("target"),
                    toast = w.optString("toast"),
                    key = w.optString("key"),
                    default = w.optBoolean("default", false),
                )
            }
        } ?: emptyList()
        val home = widgets(root.optJSONArray("home"))
        val hidden = root.optJSONArray("hidden")?.let { ha ->
            (0 until ha.length()).map { ha.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        val pages = root.optJSONArray("pages")?.let { arr: JSONArray ->
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Page(
                    id = p.optString("id").ifBlank { "p$i" },
                    title = p.optString("title").ifBlank { "自定义页" },
                    widgets = widgets(p.optJSONArray("widgets")),
                )
            }
        } ?: emptyList()
        return Config(
            theme,
            root.optString("darkTheme").let { if (it == "dark" || it == "light") it else "" },
            home,
            hidden,
            pages,
        )
    }
}
