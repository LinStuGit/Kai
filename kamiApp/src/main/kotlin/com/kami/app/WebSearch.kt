package com.kami.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Native web search + page fetch for the agent — plain HttpURLConnection,
 * no API keys. Bing first (reachable from CN campus networks), DuckDuckGo
 * Lite as fallback; pages are tag-stripped to text.
 */
object WebSearch {

    private val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    /** Search the web: title / url / snippet for the top hits. */
    fun search(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "错误：空查询"
        val bing = runCatching { bingSearch(q) }.getOrElse { "bing: ${it.message}" }
        if (bing != null) return bing
        val ddg = runCatching { ddgSearch(q) }.getOrElse { "ddg: ${it.message}" }
        return ddg ?: "搜索失败：Bing 与 DuckDuckGo 均不可达（检查设备网络）"
    }

    private fun bingSearch(q: String): String? {
        val html = httpGet("https://www.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") + "&count=10")
        val re = Regex(
            "<li class=\"b_algo\"[^>]*>.*?<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a></h2>(.*?)</li>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val out = StringBuilder()
        var n = 0
        for (m in re.findAll(html)) {
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2])
            val snippet = stripTags(
                Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                    .find(m.groupValues[3])?.groupValues?.get(1).orEmpty(),
            )
            out.append("${++n}. $title\n   $url\n" + if (snippet.isNotBlank()) "   $snippet\n" else "")
            if (n >= 8) break
        }
        return out.toString().ifBlank { null }?.let { "Bing 搜索「$q」：\n$it" }
    }

    private fun ddgSearch(q: String): String? {
        val html = httpGet("https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(q, "UTF-8"))
        val re = Regex(
            "<a rel=\"nofollow\" href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val out = StringBuilder()
        var n = 0
        for (m in re.findAll(html)) {
            var url = m.groupValues[1]
            // DDG wraps links in a redirect; unwrap the uddg parameter.
            Regex("uddg=([^&]+)").find(url)?.let {
                url = URLDecoder.decode(it.groupValues[1], "UTF-8")
            }
            if (url.startsWith("https://duckduckgo.com/lite")) continue
            val title = stripTags(m.groupValues[2])
            out.append("${++n}. $title\n   $url\n")
            if (n >= 8) break
        }
        return out.toString().ifBlank { null }?.let { "DuckDuckGo 搜索「$q」：\n$it" }
    }

    /** Fetch a URL and return readable text (tags stripped, ~4k chars). */
    fun fetch(rawUrl: String): String {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "错误：仅支持 http(s) 地址"
        }
        return try {
            val body = httpGet(url, timeoutMs = 20_000)
            "网页内容（$url，截断）：\n" + stripTags(body).take(4000)
        } catch (t: Throwable) {
            "抓取失败：${t.message}"
        }
    }

    private fun httpGet(url: String, timeoutMs: Int = 15_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val charset = conn.contentEncoding ?: run {
                Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                    .find(conn.contentType ?: "")?.groupValues?.get(1) ?: "UTF-8"
            }
            return conn.inputStream.bufferedReader(charset(charset)).readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun charset(name: String): java.nio.charset.Charset = runCatching {
        java.nio.charset.Charset.forName(name)
    }.getOrDefault(java.nio.charset.StandardCharsets.UTF_8)

    private fun stripTags(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}
