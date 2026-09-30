package com.kami.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * madmodel 网关模型目录 — PC madmodel_router.py ModelCatalog 的移植：从网站
 * 首页发现 /assets/js/index-{CODE}.js，提取 modelList:[...] 元数据
 * （value/label/supportImage/thinkingParam/effortOptions/thinkingField），
 * 展开为路由变体并在聊天请求里注入对应参数：
 *
 * - `<model>`                原样透传
 * - `<model>-nothink`        chat_template_kwargs:{<thinkingParam>:false}
 * - `<model>-think-<eff>`    chat_template_kwargs:{<thinkingParam>:true} + reasoning_effort:<eff>
 * - `<model>-think-on`       仅思考开（上游没给 effort 分档时）
 * - `<model>-effort-<eff>`   仅 reasoning_effort（无思考开关的模型）
 *
 * 目录缓存 6h（router MODEL_LIST_TTL 同款）；最近一次成功抓取持久化到 prefs，
 * 失败时回退持久化数据，再退内置种子列表（[ModelStore.MADMODEL_MODELS]）。
 */
internal object ModelCatalog {

    private const val TAG = "ModelCatalog"
    private const val SITE = "https://madmodel.cs.tsinghua.edu.cn"
    private const val PREFS = "kami_model_catalog"
    private const val TTL_MS = 6L * 3600_000

    /** 网站里一个模型的原始元数据。 */
    data class Raw(
        val value: String,
        val label: String,
        val supportImage: Boolean,
        val thinkingParam: String?,
        val effortOptions: List<String>,
    )

    /** 一个可选的路由变体（UI 单选项 + 聊天注入规格）。 */
    data class Variant(
        val id: String,
        val label: String,
        val base: String,
        val thinking: Boolean?,
        val effort: String?,
        val thinkingParam: String?,
        val supportImage: Boolean,
    )

    @Volatile
    private var variants: Map<String, Variant>? = null

    @Volatile
    private var fetchedAt: Long = 0

    @Volatile
    private var lastError: String? = null

    // ---- UI / agent 侧查询 ----

    fun ids(context: Context): List<String> = ensure(context).keys.sorted()

    fun labelOf(context: Context, id: String): String = ensure(context)[id]?.label.orEmpty()

    fun count(context: Context): Int = ensure(context).size

    fun fetchedAt(): Long = fetchedAt

    fun lastError(): String? = lastError

    /** 变体 id → 注入后的请求体（model 换基础模型 + 思考/effort 参数）；非变体不动。 */
    fun applyTo(body: JSONObject, model: String): JSONObject = applyTo(body, model, ensure(AppContextHolder.get())[model])

    internal fun applyTo(body: JSONObject, model: String, v: Variant?): JSONObject {
        if (v == null || (v.thinking == null && v.effort == null)) return body // 原样透传变体/非目录模型
        body.put("model", v.base)
        if (v.thinking == true && v.thinkingParam != null) {
            body.put("chat_template_kwargs", JSONObject().put(v.thinkingParam, true))
            if (v.effort != null) body.put("reasoning_effort", v.effort)
        } else if (v.thinking == false && v.thinkingParam != null) {
            body.put("chat_template_kwargs", JSONObject().put(v.thinkingParam, false))
        } else if (v.effort != null && v.thinkingParam == null) {
            body.put("reasoning_effort", v.effort)
        }
        return body
    }

    /** 同步刷新（网络！须在后台线程调）；返回给 UI 的一句话结果。 */
    @Synchronized
    fun refresh(context: Context): String {
        val app = context.applicationContext
        return try {
            val html = get("$SITE/")
            val jsPath = Regex("/assets/js/index-([A-Za-z0-9_.-]+)\\.js").find(html)?.groupValues?.get(0)
                ?: throw IOException("页面未找到 index-{CODE}.js")
            val js = get(SITE + jsPath)
            val i = js.indexOf("modelList:[")
            if (i < 0) throw IOException("JS 中未找到 modelList")
            val raw = parseModelList(js.substring(i))
            require(raw.isNotEmpty()) { "modelList 解析为空" }
            val m = expand(raw)
            variants = m
            fetchedAt = System.currentTimeMillis()
            lastError = null
            persist(app, raw)
            val msg = "已抓取 ${raw.size} 个模型，展开 ${m.size} 个变体"
            Log.i(TAG, msg)
            msg
        } catch (t: Throwable) {
            lastError = t.message ?: "抓取失败"
            Log.w(TAG, "模型目录抓取失败：$lastError")
            ensure(app) // 回退持久化/种子列表
            "抓取失败：$lastError（沿用上次/内置列表，共 ${variants?.size ?: 0} 项）"
        }
    }

    // ---- 纯解析（JVM 可测） ----

    /** 从 JS 文本提取 modelList 数组（[start] 指向 "modelList:" 的 m 处或之后）。 */
    internal fun parseModelList(jsFromModelList: String): List<Raw> {
        val start = jsFromModelList.indexOf('[')
        if (start < 0) throw IOException("modelList 数组未找到")
        var depth = 0
        var inStr = false
        var k = start
        var end = -1
        while (k < jsFromModelList.length) {
            val c = jsFromModelList[k]
            if (inStr) {
                if (c == '\\') {
                    k += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true

                    '[' -> depth++

                    ']' -> {
                        depth--
                        if (depth == 0) {
                            end = k + 1
                            break
                        }
                    }
                }
            }
            k++
        }
        if (end < 0) throw IOException("modelList 数组未闭合")
        val seg = jsFromModelList.substring(start, end)
        val out = ArrayList<Raw>()
        for (mm in Regex("""\{[^{}]*value\s*:\s*["']([^"']+)["'][^{}]*\}""").findAll(seg)) {
            val obj = mm.groupValues[0]
            val value = mm.groupValues[1]
            val sp = Regex("""supportImage\s*:\s*(!0|!1|true|false)""").find(obj)
            val eff = Regex("""effortOptions\s*:\s*\[([^\]]*)\]""").find(obj)?.groupValues?.get(1).orEmpty()
            out.add(
                Raw(
                    value = value,
                    label = q(obj, "label") ?: value,
                    supportImage = sp != null && sp.groupValues[1] in setOf("!0", "true"),
                    thinkingParam = q(obj, "thinkingParam"),
                    effortOptions = Regex("\"([^\"]*)\"").findAll(eff).map { it.groupValues[1] }.toList(),
                ),
            )
        }
        return out
    }

    /** 元数据 → 路由变体表（router _expand_variants 同款语义）。 */
    internal fun expand(models: List<Raw>): Map<String, Variant> {
        val out = LinkedHashMap<String, Variant>()
        for (m in models) {
            val b = m.value
            val p = m.thinkingParam
            out[b] = Variant(b, m.label + "（原样透传）", b, null, null, p, m.supportImage)
            if (p != null) {
                out["$b-nothink"] = Variant("$b-nothink", m.label + "（关闭思考）", b, false, null, p, m.supportImage)
                if (m.effortOptions.isNotEmpty()) {
                    for (e in m.effortOptions) {
                        out["$b-think-$e"] = Variant("$b-think-$e", "${m.label}（思考：$e）", b, true, e, p, m.supportImage)
                    }
                } else {
                    out["$b-think-on"] = Variant("$b-think-on", m.label + "（思考开）", b, true, null, p, m.supportImage)
                }
            } else {
                for (e in m.effortOptions) {
                    out["$b-effort-$e"] = Variant("$b-effort-$e", "${m.label}（effort：$e）", b, null, e, null, m.supportImage)
                }
            }
        }
        return out
    }

    private fun q(obj: String, key: String): String? = Regex(key + """\s*:\s*["]([^"]*)["]""").find(obj)?.groupValues?.get(1)

    // ---- 缓存 / 持久化 ----

    @Synchronized
    private fun ensure(context: Context): Map<String, Variant> {
        variants?.let { return it }
        val m = runCatching { fromPrefs(context) }.getOrNull() ?: seed()
        variants = m
        return m
    }

    private fun fromPrefs(context: Context): Map<String, Variant>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getString("raw", "") ?: return null
        if (s.isBlank()) return null
        val arr = JSONObject(s).optJSONArray("raw") ?: return null
        val list = ArrayList<Raw>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val eff = o.optJSONArray("efforts") ?: JSONArray()
            list.add(
                Raw(
                    value = o.getString("value"),
                    label = o.optString("label", o.getString("value")),
                    supportImage = o.optBoolean("supportImage"),
                    thinkingParam = o.optString("thinkingParam").takeIf { it != "null" && it.isNotBlank() },
                    effortOptions = List(eff.length()) { eff.getString(it) },
                ),
            )
        }
        if (list.isEmpty()) return null
        fetchedAt = p.getLong("fetchedAt", 0)
        return expand(list)
    }

    private fun persist(context: Context, raw: List<Raw>) {
        val arr = JSONArray()
        for (m in raw) {
            val eff = JSONArray()
            for (e in m.effortOptions) eff.put(e)
            arr.put(
                JSONObject()
                    .put("value", m.value)
                    .put("label", m.label)
                    .put("supportImage", m.supportImage)
                    .put("thinkingParam", m.thinkingParam ?: JSONObject.NULL)
                    .put("efforts", eff),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("raw", JSONObject().put("raw", arr).toString())
            .putLong("fetchedAt", fetchedAt)
            .apply()
    }

    /** 抓不到时的兜底：内置种子模型，全部原样透传。 */
    private fun seed(): Map<String, Variant> {
        val out = LinkedHashMap<String, Variant>()
        for (m in ModelStore.MADMODEL_MODELS) {
            out[m] = Variant(m, m + "（内置种子）", m, null, null, null, false)
        }
        return out
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            )
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code：$url")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
