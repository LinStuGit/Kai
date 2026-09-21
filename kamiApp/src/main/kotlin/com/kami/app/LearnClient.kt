package com.kami.app

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * learn.tsinghua.edu.cn read-only client — a Kotlin port of thu-learn-lib's
 * read paths (courses / homework / notifications / files). The cookie session
 * is seeded by the in-app WebView login (设置 → 校园账号 → 完成登录), exactly
 * like the WeClaude browser-login export; no password is handled here.
 */
/** One unsubmitted homework row, for the minus-one todo card. */
data class HomeworkItem(val course: String, val title: String, val deadline: String, val wlkcid: String = "", val zyid: String = "")

/** Epoch seconds/millis ("1790123456") to "MM-dd HH:mm"; anything else passes through. */
internal fun fmtEpochTs(v: String?): String {
    val t = v?.trim() ?: return ""
    if (!Regex("\\d{9,13}").matches(t)) return t
    val ms = if (t.length >= 13) t.toLong() else t.toLong() * 1000L
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

internal object LearnClient {

    private const val LEARN = "https://learn.tsinghua.edu.cn"
    private const val COURSE_LIST_PAGE = "$LEARN/f/wlxt/index/course/student/"
    private const val CURRENT_SEMESTER = "$LEARN/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester"
    private const val COURSE_LIST = "$LEARN/b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId/"
    private const val MAX_SIZE = 200

    /** host -> (name -> value), mirroring the JS lib's cookie jar. */
    private val jar = LinkedHashMap<String, MutableMap<String, String>>()

    @Volatile
    private var csrf = ""

    private class NotLoggedIn(msg: String) : Exception(msg)

    /** Pour "k=v; k2=v2" strings (from the WebView CookieManager) into the jar. */
    private fun pour(str: String, host: String) {
        if (str.isBlank()) return
        val m = jar.getOrPut(host) { mutableMapOf<String, String>() }
        for (pair in str.split(";")) {
            val i = pair.indexOf('=')
            if (i > 0) m[pair.take(i).trim()] = pair.substring(i + 1).trim()
        }
    }

    fun seedFromStore() {
        jar.clear()
        csrf = ""
        CampusStore.get()?.let { s ->
            pour(s.learnCookies, "learn.tsinghua.edu.cn")
            pour(s.idCookies, "id.tsinghua.edu.cn")
            csrf = s.csrf
        }
    }

    /** Seed from the WebView's live cookies and try to establish a session.
     *  Returns the CampusStore.Session on success (login page still answers
     *  the course list with a _csrf token only when authenticated). */
    fun exportFromWebview(): CampusStore.Session {
        val cm = CookieManager.getInstance()
        val learn = cm.getCookie("$LEARN/") ?: ""
        val idc = cm.getCookie("https://id.tsinghua.edu.cn/") ?: ""
        jar.clear()
        csrf = ""
        pour(learn, "learn.tsinghua.edu.cn")
        pour(idc, "id.tsinghua.edu.cn")
        val token = try {
            fetchCsrf()
        } catch (t: Throwable) {
            throw Exception("未能建立有效会话——请在页面完成 CAS 登录（需校园网/校内直连）后重试")
        }
        val session = CampusStore.Session(
            username = idc.split(";").firstOrNull { it.trim().startsWith("TSINGHUAUSERID=") }
                ?.substringAfter('=')?.trim() ?: "",
            savedAt = System.currentTimeMillis(),
            csrf = token,
            learnCookies = learn,
            idCookies = idc,
        )
        CampusStore.save(session)
        return session
    }

    private fun cookieHeader(host: String): String = jar[host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: ""

    private fun absorb(conn: HttpURLConnection, host: String) {
        val m = jar.getOrPut(host) { mutableMapOf<String, String>() }
        for (h in conn.headerFields["Set-Cookie"].orEmpty()) {
            val nv = h.split(";").firstOrNull() ?: continue
            val i = nv.indexOf('=')
            if (i > 0) m[nv.take(i).trim()] = nv.substring(i + 1).trim()
        }
    }

    private class Resp(val body: String, val finalUrl: String)

    private fun http(url: String, method: String = "GET", form: String? = null): Resp {
        val u = URL(url)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Cookie", cookieHeader(u.host))
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
            if (form != null) {
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }
        }
        try {
            if (form != null) conn.outputStream.use { it.write(form.toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            absorb(conn, u.host)
            return Resp(body, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    /** Pull the _csrf token from the course list page — only answers when the
     *  cookie session is live (thu-learn-lib fetchCSRFToken 的等价实现). */
    private fun fetchCsrf(): String {
        val r = http(COURSE_LIST_PAGE)
        if (r.finalUrl.contains("login_timeout") || r.finalUrl.contains("/f/id/login")) {
            throw NotLoggedIn("会话失效")
        }
        val m = Regex("&_csrf=(\\S*)\"").find(r.body) ?: throw NotLoggedIn("会话失效")
        csrf = m.groupValues[1]
        return csrf
    }

    private fun ensure(): String = csrf.ifEmpty { fetchCsrf() }

    private fun withToken(url: String): String {
        val sep = if (url.contains('?')) "&" else "?"
        return url + sep + "_csrf=" + URLEncoder.encode(ensure(), "UTF-8")
    }

    private fun getJson(url: String): JSONObject {
        val r = http(withToken(url))
        if (r.finalUrl.contains("login_timeout")) throw NotLoggedIn("会话失效")
        return JSONObject(r.body)
    }

    private fun postJson(url: String, form: String): JSONObject {
        val r = http(withToken(url), method = "POST", form = form)
        if (r.finalUrl.contains("login_timeout")) throw NotLoggedIn("会话失效")
        return JSONObject(r.body)
    }

    private fun pageListForm(courseId: String): String = "aoData=" + URLEncoder.encode(
        JSONArray().put(JSONObject().put("name", "wlkcid").put("value", courseId)).toString(),
        "UTF-8",
    )

    private fun requireOk(json: JSONObject, key: String = "result"): JSONObject? {
        if (json.optString(key) != "success") throw Exception("接口返回异常")
        return json.optJSONObject("object")
    }

    fun currentSemesterId(): String {
        val json = getJson(CURRENT_SEMESTER)
        return json.optJSONObject("result")?.optString("id") ?: throw Exception("无法获取当前学期")
    }

    private fun courseRows(semester: String): JSONArray {
        val json = getJson("$COURSE_LIST$semester/zh_CN")
        if (json.optString("message") != "success") throw Exception("课程列表返回异常")
        return json.optJSONArray("resultList") ?: JSONArray()
    }

    private fun courseNameMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val rows = courseRows(currentSemesterId())
        for (ci in 0 until rows.length()) {
            val c = rows.getJSONObject(ci)
            map[c.optString("wlkcid")] = c.optString("kcm").ifBlank { c.optString("zywkcm") }
        }
        return map
    }

    private fun html(s: String): String = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

    private fun javaDecodeBase64(s: String): String = try {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
    } catch (t: Throwable) {
        ""
    }

    // ── Agent-facing aggregated commands (compact text) ──────────────────

    fun courses(): String {
        seedFromStore()
        val rows = courseRows(currentSemesterId())
        if (rows.length() == 0) return "（本学期无课程）"
        val out = StringBuilder("共 ${rows.length()} 门课：\n")
        for (ci in 0 until rows.length()) {
            val c = rows.getJSONObject(ci)
            out.append(
                "• " + html(c.optString("kcm")) +
                    "｜教师 " + c.optString("jsm", "").ifBlank { "?" } +
                    "｜课程号 " + c.optString("kch") +
                    "｜课序 " + c.optString("kxh") + "\n",
            )
        }
        return out.toString().trim()
    }

    private fun <T> perCourse(block: (id: String, name: String) -> List<T>): List<Pair<String, T>> {
        seedFromStore()
        val names = courseNameMap()
        val out = mutableListOf<Pair<String, T>>()
        for ((id, name) in names) {
            try {
                block(id, name).forEach { out.add(name to it) }
            } catch (t: Throwable) {
                // 单课失败容忍（与 WeClaude perCourse 一致）
            }
        }
        return out
    }

    fun homework(): String {
        val items = perCourse { id, _ ->
            val out = mutableListOf<String>()
            // 三个状态列表：未交 / 已交未评 / 已评（thu-learn-lib LEARN_HOMEWORK_LIST_SOURCE）
            val sources = listOf(
                "$LEARN/b/wlxt/kczy/zy/student/zyListWj" to "未提交",
                "$LEARN/b/wlxt/kczy/zy/student/zyListYjwg" to "已提交未批阅",
                "$LEARN/b/wlxt/kczy/zy/student/zyListYpg" to "已批阅",
            )
            for ((url, status) in sources) {
                try {
                    val obj = requireOk(postJson(url, pageListForm(id))) ?: continue
                    val arr = obj.optJSONArray("aaData") ?: continue
                    for (hi in 0 until arr.length()) {
                        val h = arr.getJSONObject(hi)
                        val grade = if (h.isNull("cj")) "" else "｜成绩 ${h.opt("cj")}"
                        out.add(
                            html(h.optString("bt")) + "｜" + status +
                                "｜截止 " + h.optString("jzsj", "?") + grade,
                        )
                    }
                } catch (t: Throwable) {
                }
            }
            out
        }
        if (items.isEmpty()) return "（无作业记录，或会话失效 — status 可查）"
        return "共 ${items.size} 条作业：\n" + items.joinToString("\n") { "• ${it.first}｜${it.second}" }
    }

    fun notifications(): String {
        val items = perCourse { id, _ ->
            val out = mutableListOf<String>()
            for (suffix in listOf("Wgq", "Ygq")) {
                try {
                    val url = "$LEARN/b/wlxt/kcgg/wlkc_ggb/student/pageListXsby$suffix"
                    val obj = requireOk(postJson(url, pageListForm(id))) ?: continue
                    val arr = obj.optJSONArray("aaData") ?: continue
                    for (ni in 0 until arr.length()) {
                        val n = arr.getJSONObject(ni)
                        val important = if (n.optString("sfqd") == "1") "｜★重要" else ""
                        out.add(
                            html(n.optString("bt")) + "｜发布 " +
                                n.optString("fbsjStr").ifBlank { fmtEpochTs(n.optString("fbsj")) } + important,
                        )
                    }
                } catch (t: Throwable) {
                }
            }
            out
        }
        if (items.isEmpty()) return "（无课程公告，或会话失效）"
        return "共 ${items.size} 条公告：\n" + items.joinToString("\n") { "• ${it.first}｜${it.second}" }
    }

    fun files(): String {
        val items = perCourse { id, _ ->
            val out = mutableListOf<String>()
            try {
                val url = "$LEARN/b/wlxt/kj/wlkc_kjxxb/student/kjxxbByWlkcidAndSizeForStudent?wlkcid=$id&size=$MAX_SIZE"
                val obj = requireOk(getJson(url))
                val arr = obj?.optJSONArray("resultsList") ?: obj?.optJSONArray("object") ?: JSONArray()
                for (fi in 0 until arr.length()) {
                    val f = arr.getJSONObject(fi)
                    val kb = f.optLong("wjdx") / 1024
                    out.add(html(f.optString("bt")) + "｜${kb}KB｜上传 " + fmtEpochTs(f.optString("scsj")))
                }
            } catch (t: Throwable) {
            }
            out
        }
        if (items.isEmpty()) return "（无课程文件，或会话失效）"
        return "共 ${items.size} 个文件：\n" + items.joinToString("\n") { "• ${it.first}｜${it.second}" }
    }

    /** 未提交作业（负一屏待办用）：每课一查，单课失败容忍。 */
    fun homeworkPending(): List<HomeworkItem> = perCourse { id, name ->
        val out = mutableListOf<HomeworkItem>()
        try {
            val obj = requireOk(postJson("$LEARN/b/wlxt/kczy/zy/student/zyListWj", pageListForm(id)))
            val arr = obj?.optJSONArray("aaData")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val h = arr.getJSONObject(i)
                    out.add(
                        HomeworkItem(
                            name,
                            html(h.optString("bt")),
                            fmtEpochTs(h.optString("jzsj")),
                            id,
                            h.optString("zyid"),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
        }
        out
    }.map { it.second }

    /** One announcement row (structured, for the minus-one table + detail). */
    data class NotifItem(
        val course: String,
        val wlkcid: String,
        val ggid: String,
        val title: String,
        val whenStr: String,
        val important: Boolean,
        val contentText: String,
        val attName: String?,
    )

    /** Structured announcement rows across all courses (base64 ggnr decoded,
     *  per thu-learn-lib: content ships inside the list JSON itself). */
    fun notificationRows(): List<NotifItem> = perCourse { id, name ->
        val out = mutableListOf<NotifItem>()
        for (suffix in listOf("Wgq", "Ygq")) {
            try {
                val url = "$LEARN/b/wlxt/kcgg/wlkc_ggb/student/pageListXsby$suffix"
                val obj = requireOk(postJson(url, pageListForm(id))) ?: continue
                val arr = obj.optJSONArray("aaData") ?: continue
                for (ni in 0 until arr.length()) {
                    val n = arr.getJSONObject(ni)
                    val contentHtml = try {
                        String(android.util.Base64.decode(n.optString("ggnr"), android.util.Base64.DEFAULT))
                    } catch (t: Throwable) {
                        ""
                    }
                    out.add(
                        NotifItem(
                            course = name,
                            wlkcid = id,
                            ggid = n.optString("ggid"),
                            title = html(n.optString("bt")),
                            whenStr = n.optString("fbsjStr").ifBlank { fmtEpochTs(n.optString("fbsj")) },
                            important = n.optString("sfqd") == "1",
                            contentText = html(contentHtml).trim(),
                            attName = n.optString("fjmc").ifBlank { null },
                        ),
                    )
                }
            } catch (t: Throwable) {
            }
        }
        out
    }.map { it.second }

    /** Announcement attachment download URL (student beforeView page scrape). */
    fun notificationAttachmentUrl(wlkcid: String, ggid: String): String? {
        val r = http(
            "$LEARN/f/wlxt/kcgg/wlkc_ggb/student/beforeViewXs?wlkcid=" +
                URLEncoder.encode(wlkcid, "UTF-8") + "&id=" + URLEncoder.encode(ggid, "UTF-8"),
        )
        val m = Regex("href\\s*=\\s*\"([^\"]*wjid=[^\"]*)\"").find(r.body) ?: return null
        val path = m.groupValues[1].replace("&amp;", "&")
        return if (path.startsWith("http")) path else LEARN + path
    }

    /** Description text of one homework (POST detail, form id=zyid). */
    fun homeworkDetail(wlkcid: String, zyid: String): String {
        if (zyid.isBlank()) return "（无作业详情 id）"
        val json = postJson("$LEARN/b/wlxt/kczy/zy/student/detail", "id=" + URLEncoder.encode(zyid, "UTF-8"))
        if (json.optString("result") != "success") throw Exception("作业详情返回异常")
        val msg = html(json.optString("msg")).trim()
        return msg.ifBlank { "（无文字描述）" }
    }

    /** One course-file row (structured, for per-course tables + download). */
    data class FileRow(val wjid: String, val title: String, val sizeKb: Long, val uploaded: String, val desc: String)

    /** Course file list for the minus-one course drill-down. */
    fun courseFiles(wlkcid: String): List<FileRow> {
        seedFromStore()
        val obj = requireOk(
            getJson("$LEARN/b/wlxt/kj/wlkc_kjxxb/student/kjxxbByWlkcidAndSizeForStudent?wlkcid=$wlkcid&size=$MAX_SIZE"),
        )
        val arr = obj?.optJSONArray("resultsList") ?: obj?.optJSONArray("object") ?: JSONArray()
        val out = mutableListOf<FileRow>()
        for (fi in 0 until arr.length()) {
            val f = arr.getJSONObject(fi)
            out.add(
                FileRow(
                    wjid = f.optString("wjid"),
                    title = html(f.optString("bt")),
                    sizeKb = f.optLong("wjdx") / 1024,
                    uploaded = fmtEpochTs(f.optString("scsj")),
                    desc = html(f.optString("ms")).trim(),
                ),
            )
        }
        return out
    }

    /** Stream `url` (cookie-authenticated) into Download/Kami (MediaStore on 29+). */
    fun downloadToFile(context: android.content.Context, url: String, name: String): String {
        seedFromStore()
        val u = URL(url)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Cookie", cookieHeader(u.host))
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36")
        }
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP " + conn.responseCode)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                    put(
                        android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/Kami",
                    )
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("系统拒绝写入 Download/Kami")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                } ?: throw Exception("无法打开输出流")
                return "已下载到 Download/Kami/：$name"
            }
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val f = java.io.File(dir, name)
            conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            return "已下载到应用目录：" + f.absolutePath
        } finally {
            conn.disconnect()
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "zip", "rar", "7z" -> "application/zip"
        "txt" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** One course row (structured, for the minus-one course table). */
    data class CourseRow(val wlkcid: String, val name: String, val teacher: String, val kch: String)

    /** Structured course list for the minus-one courses card. */
    fun courseList(): List<CourseRow> {
        seedFromStore()
        val rows = courseRows(currentSemesterId())
        val out = mutableListOf<CourseRow>()
        for (ci in 0 until rows.length()) {
            val c = rows.getJSONObject(ci)
            out.add(
                CourseRow(
                    wlkcid = c.optString("wlkcid"),
                    name = html(c.optString("kcm").ifBlank { c.optString("zywkcm") }),
                    teacher = c.optString("jsm", "").ifBlank { "?" },
                    kch = c.optString("kch"),
                ),
            )
        }
        return out
    }

    /** The agent tool entry point. */
    fun agentCommand(action: String): String = when (action) {
        "courses" -> courses()
        "homework" -> homework()
        "notifications" -> notifications()
        "files" -> files()
        else -> status()
    }

    fun status(): String {
        val s = CampusStore.get()
        val loggedIn = try {
            seedFromStore()
            fetchCsrf()
            true
        } catch (t: Throwable) {
            false
        }
        return when {
            s == null -> "未登录：请让用户在 设置 → 校园账号 点「登录网络学堂」完成 WebView 登录（校园网环境）"
            loggedIn -> "已登录${if (s.username.isNotBlank()) "（${s.username}）" else ""}，可查 courses/homework/notifications/files"
            else -> "会话已失效：请让用户在 设置 → 校园账号 重新登录"
        }
    }
}
