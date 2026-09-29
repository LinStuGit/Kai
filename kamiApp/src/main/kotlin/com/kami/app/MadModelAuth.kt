package com.kami.app

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * madmodel key acquisition via the info-portal roaming chain (2026-09 gateway
 * policy: the check endpoint only exchanges a CAS ticket, and one account
 * holds a single live key — a fresh fetch invalidates the previous token).
 *
 * Chain ported from thu-info-lib `getMadModelToken` → `roam("default", YYFWID)`:
 * webvpn session → wengine info cookie (XSRF-TOKEN) → portal onlineAppRedirect
 * with the madmodel yyfwid → ticket in the redirect URL → wrapped
 * /model-api/auth-login/check?ticket → `{"data": "<jwt>"}`.
 *
 * The webvpn session comes from the in-app WebView login (设置 → 校园账号 →
 * 信息门户); cookies persist in prefs and are refreshed from the live
 * CookieManager on every fetch, so browsing webvpn in the app re-warms it.
 */
object MadModelAuth {

    private const val WEBVPN = "https://webvpn.tsinghua.edu.cn"

    /** webvpn-wrapped madmodel.cs.tsinghua.edu.cn (thu-info-lib HOST_MAP). */
    private const val MAD_WRAPPED =
        "$WEBVPN/https/77726476706e69737468656265737421fdf6459128346d5c300b9ae28c462a3b27469fc32211fa26a3e464"

    /** webvpn-wrapped info.tsinghua.edu.cn（门户应用中心所在域）——also the login entry. */
    internal const val INFO_WRAPPED =
        "$WEBVPN/https/77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de"

    /** Asks the wengine for the inner info.tsinghua.edu.cn cookie (XSRF-TOKEN). */
    private const val GET_COOKIE_URL =
        "$WEBVPN/wengine-vpn/cookie?method=get&host=info.tsinghua.edu.cn" +
            "&scheme=https&path=/f/info/gxfw_fg/common/index"

    private const val ROAMING_URL =
        "$INFO_WRAPPED/b/yyfw/vyyfwxx/info/portal_fg/common/onlineAppRedirect"

    /** 应用中心里 madmodel.cs 应用的 yyfwid（thu-info-lib getMadModelToken 同款）. */
    private const val YYFWID = "19D04E39D96B36C494F2E48A1A4741FD"

    private const val PREFS = "kami_madmodel_auth"

    private val jar = LinkedHashMap<String, String>()

    private fun prefs() = AppContextHolder.get().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pour(str: String) {
        if (str.isBlank()) return
        for (pair in str.split(";")) {
            val i = pair.indexOf('=')
            if (i > 0) jar[pair.take(i).trim()] = pair.substring(i + 1).trim()
        }
    }

    /** Stored cookies first, live WebView cookies over them (freshest wins). */
    private fun seed() {
        jar.clear()
        pour(prefs().getString("webvpnCookies", "") ?: "")
        try {
            CookieManager.getInstance().getCookie("$WEBVPN/")?.let { pour(it) }
        } catch (_: Throwable) {
            // CookieManager unavailable (JVM tests): stored snapshot only
        }
    }

    private fun persist() {
        prefs().edit()
            .putString("webvpnCookies", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    fun savedAt(): Long = prefs().getLong("savedAt", 0)

    fun clear(): String {
        prefs().edit().clear().apply()
        return "已清除 webvpn 会话"
    }

    /** Called from the ✓ 完成登录 button: grab the WebView cookies, prove the
     *  chain works by immediately fetching a token. */
    fun exportFromWebview(): String {
        seed()
        if (jar.isEmpty()) {
            throw Exception("未检测到 webvpn 会话——请先在页面完成统一身份登录（含二次认证）")
        }
        persist()
        return try {
            fetchToken()
            "已登录，模型 key 已获取"
        } catch (t: Throwable) {
            "会话已保存，但取 key 失败：${t.message}"
        }
    }

    /**
     * The roaming chain. Throws [IOException] with an actionable message when
     * any hop fails (most commonly: webvpn session expired → re-login).
     */
    fun fetchToken(): String {
        seed()
        if (jar.isEmpty()) throw IOException("webvpn 未登录——设置→校园账号→登录信息门户")
        // ① inner info session → XSRF-TOKEN
        var csrf = xsrf()
        if (csrf == null) {
            get("$INFO_WRAPPED/") // wake the inner info session
            csrf = xsrf()
                ?: throw IOException("未取得门户 XSRF-TOKEN——webvpn 会话可能已失效，请重新登录信息门户")
        }
        // ② portal roaming redirect
        val roam = get(
            "$ROAMING_URL?yyfwid=$YYFWID&_csrf=${URLEncoder.encode(csrf, "UTF-8")}&machine=p",
        )
        val roamingUrl = try {
            JSONObject(roam).optJSONObject("object")?.optString("roamingurl") ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (roamingUrl.isBlank()) {
            throw IOException("门户漫游未返回跳转 URL（会话失效或应用未授权）：${roam.take(120)}")
        }
        // ③ ticket from the redirect URL; visit it, then exchange at check
        val ticket = Regex("ticket=(.+)").find(roamingUrl)?.groupValues?.get(1)
            ?: throw IOException("跳转 URL 不含 ticket：${roamingUrl.take(120)}")
        get(roamingUrl)
        val body = get("$MAD_WRAPPED/model-api/auth-login/check?ticket=$ticket")
        val key = try {
            JSONObject(body).optString("data")
        } catch (_: Throwable) {
            ""
        }
        if (key.isBlank()) throw IOException("check 未返回 key：${body.take(120)}")
        persist()
        return key
    }

    private fun xsrf(): String? = Regex("XSRF-TOKEN=(.+?);").find(get(GET_COOKIE_URL) + ";")?.groupValues?.get(1)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (jar.isNotEmpty()) {
                conn.setRequestProperty(
                    "Cookie",
                    jar.entries.joinToString("; ") { "${it.key}=${it.value}" },
                )
            }
            conn.setRequestProperty("Accept", "application/json, text/html, */*")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            )
            val stream = if (conn.responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: throw IOException("HTTP ${conn.responseCode}: $url")
            }
            val text = stream.bufferedReader().readText()
            for (h in conn.headerFields["Set-Cookie"].orEmpty()) {
                h.split(";").firstOrNull()?.let { pour(it) }
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
