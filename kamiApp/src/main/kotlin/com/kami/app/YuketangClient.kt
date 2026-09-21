package com.kami.app

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 荷塘雨课堂 (pro.yuketang.cn) read-only data plane — Kotlin port of the
 * WeClaude campus/yuketang.mjs surface (courses + assignment/exam log,
 * endpoints per yuketang-helper-auto). The session cookie is seeded by the
 * in-app WebView login (设置 → 校园账号 → 登录雨课堂); requests replay the
 * web client's own headers (xtbz: ykt / xt-agent: web). Read-only by design:
 * no auto-answer, no submit — 约束「提交类操作不代做」.
 */
internal object YuketangClient {

    private const val ORIGIN = "https://pro.yuketang.cn"
    private const val PREFS = "kami_yuketang"
    private const val PAGE_SIZE = 200
    private const val MAX_PAGES = 20

    @Volatile
    private var cookieCache: String? = null

    private fun prefs() = AppContextHolder.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved session cookie ("" = never logged in). */
    fun cookie(): String {
        cookieCache?.let { return it }
        val c = prefs().getString("cookie", "") ?: ""
        cookieCache = c
        return c
    }

    fun savedAt(): Long = prefs().getLong("savedAt", 0L)

    fun clear(): String {
        prefs().edit().clear().apply()
        cookieCache = ""
        return "已清除雨课堂会话"
    }

    /** Export the WebView's yuketang cookies; returns a status line. */
    fun exportFromWebview(): String {
        val c = CookieManager.getInstance().getCookie("$ORIGIN/") ?: ""
        if (c.isBlank()) return "未取到雨课堂 cookie——请在页面完成登录后再点完成登录"
        prefs().edit().putString("cookie", c).putLong("savedAt", System.currentTimeMillis()).apply()
        cookieCache = c
        return try {
            val n = courseList().length()
            "已登录雨课堂（$n 门课）"
        } catch (t: Throwable) {
            "已保存 cookie，但校验失败：" + (t.message ?: t.javaClass.simpleName) + "——功能可能受限"
        }
    }

    private val tsFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private fun http(path: String): JSONObject {
        val conn = URL(ORIGIN + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Cookie", cookie())
            conn.setRequestProperty("xtbz", "ykt")
            conn.setRequestProperty("xt-agent", "web")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            )
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code（可能需重新登录雨课堂，或不在校园网/登录态环境）")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun dataOf(json: JSONObject, hint: String): JSONObject {
        val data = json.optJSONObject("data")
        if (data == null) {
            val msg = json.optString("msg", json.optString("message", ""))
            throw Exception(if (msg.isBlank()) hint else msg)
        }
        return data
    }

    /** Student class-course rows (entries carrying classroom_id). */
    private fun courseList(): JSONArray {
        val data = dataOf(http("/v2/api/web/courses/list?identity=2"), "雨课堂课程列表格式异常（未登录？）")
        return data.optJSONArray("list") ?: JSONArray()
    }

    private fun courseName(c: JSONObject): String = c.optString("name").ifBlank { c.optJSONObject("course")?.optString("name", "雨课堂课程") ?: "雨课堂课程" }

    fun courses(): String {
        val rows = courseList()
        val out = StringBuilder("共 ")
            .append(rows.length()).append(" 门雨课堂课程：\n")
        for (i in 0 until rows.length()) {
            val c = rows.getJSONObject(i)
            val cid = c.optString("classroom_id")
            if (cid.isBlank()) continue
            out.append("• ").append(courseName(c)).append("｜班课 ").append(cid).append('\n')
        }
        return out.toString().trim()
    }

    fun status(): String = when {
        cookie().isBlank() ->
            "未登录雨课堂：请让用户在 设置 → 校园账号 点「登录雨课堂」完成 WebView 登录"

        else -> try {
            courseList()
            "已登录雨课堂，可查 courses/assignments"
        } catch (t: Throwable) {
            "会话可能已失效：" + (t.message ?: "?") + " —— 请让用户在 设置 → 校园账号 重新登录雨课堂"
        }
    }

    /** One assignment/exam entry from the study log (type 19 作业 / 20 考试). */
    data class YkItem(
        val course: String,
        val classroomId: String,
        val title: String,
        val kind: String,
        val deadline: String,
        val url: String,
    )

    /** Aggregate homework/exams across class courses (study log, type 19/20). */
    fun assignments(): List<YkItem> {
        val rows = courseList()
        val uniq = LinkedHashMap<String, String>()
        for (i in 0 until rows.length()) {
            val c = rows.getJSONObject(i)
            val cid = c.optString("classroom_id")
            if (cid.isNotBlank() && !uniq.containsKey(cid)) uniq[cid] = courseName(c)
        }
        val out = mutableListOf<YkItem>()
        for ((cid, courseName) in uniq) {
            val seen = HashSet<String>()
            for (page0 in 0 until MAX_PAGES) {
                val acts: JSONArray? = try {
                    dataOf(
                        http(
                            "/v2/api/web/logs/learn/" + java.net.URLEncoder.encode(cid, "UTF-8") +
                                "?page=" + page0 + "&offset=" + PAGE_SIZE + "&sort=0&actype=-1",
                        ),
                        "学习日志格式异常",
                    ).optJSONArray("activities")
                } catch (t: Throwable) {
                    null
                }
                if (acts == null) break
                val sig = acts.toString()
                if (!seen.add(sig)) break
                for (i in 0 until acts.length()) {
                    val a = acts.optJSONObject(i) ?: continue
                    val type = a.optInt("type", 0)
                    if (type != 19 && type != 20) continue
                    val content = a.optJSONObject("content") ?: JSONObject()
                    val leaf = content.optString("leaf_id")
                    val key = cid + ":" + type + ":" + a.optString("id").ifBlank {
                        a.optString("courseware_id").ifBlank { leaf }
                    }
                    if (!seen.add(key)) continue
                    val scoreD = content.optLong("score_d", 0L)
                    out.add(
                        YkItem(
                            course = courseName,
                            classroomId = a.optString("classroom_id").ifBlank { cid },
                            title = a.optString("title").ifBlank { if (type == 20) "考试" else "作业" },
                            kind = if (type == 20) "考试" else "作业",
                            deadline = if (scoreD > 0) tsFmt.format(Date(scoreD)) else "",
                            url = if (leaf.isNotBlank()) {
                                "$ORIGIN/ai-workspace/lms-graph/" + java.net.URLEncoder.encode(cid, "UTF-8") +
                                    "/" + (if (type == 20) "quiz" else "exercise") + "/" +
                                    java.net.URLEncoder.encode(leaf, "UTF-8") + "?is_chapter=1"
                            } else {
                                "$ORIGIN/v2/web/studentLog/" + java.net.URLEncoder.encode(cid, "UTF-8")
                            },
                        ),
                    )
                }
                if (acts.length() < PAGE_SIZE) break
            }
        }
        return out
    }

    fun assignmentsStr(): String {
        val items = assignments()
        if (items.isEmpty()) return "（雨课堂无作业/考试记录，或未登录）"
        val sb = StringBuilder("共 ").append(items.size).append(" 条雨课堂作业/考试：\n")
        items.forEach {
            sb.append("• ").append(it.course).append("｜").append(it.title)
                .append("（").append(it.kind).append("）｜截止 ").append(it.deadline.ifBlank { "—" }).append("\n")
        }
        return sb.toString()
    }

    /** The agent tool entry point. */
    fun agentCommand(action: String): String = when (action) {
        "courses" -> courses()
        "assignments", "homework" -> assignmentsStr()
        else -> status()
    }
}
