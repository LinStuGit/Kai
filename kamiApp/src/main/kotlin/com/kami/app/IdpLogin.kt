package com.kami.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Direct id.tsinghua.edu.cn (old do/off IdP) login that ends in a fresh
 * madmodel JWT — the Kotlin port of the new PC madmodel_router.py flow
 * (2026-09-29 gateway policy: /model-api/auth-login/check only exchanges a
 * CAS ticket, and one account holds a single live key).
 *
 * Chain: login form (md5("DEEPSEEK") app id) → SM2-encrypted password POST →
 * success page / manual redirect chain grabs ?ticket= → direct
 * check?ticket= → data = JWT. No webvpn, no portal session involved; a
 * trusted device fingerprint (saved once via saveFinger, e.g. by the PC
 * router) skips the second-factor step entirely.
 */
internal object IdpLogin {

    private const val ID_HOST = "https://id.tsinghua.edu.cn"
    private const val APP_ID = "DEEPSEEK"
    private val APP_MD5 = MessageDigest.getInstance("MD5")
        .digest(APP_ID.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }
    private val FORM_URL = "$ID_HOST/do/off/ui/auth/login/form/$APP_MD5/0?/authLogin"
    private const val LOGIN_URL = "$ID_HOST/do/off/ui/auth/login/check"
    private const val DOUBLE_AUTH_URL = "$ID_HOST/b/doubleAuth/login"
    private const val SAVE_FINGER_URL = "$ID_HOST/b/doubleAuth/personal/saveFinger"

    private val CHECK_URL =
        MadModel.BASE.removeSuffix("/v1") + "/model-api/auth-login/check"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private class Resp(val code: Int, val body: String, val location: String?, val finalUrl: String)

    /** Session-scoped cookie jar + redirect-control HTTP helper. */
    private class Http(private val jar: MutableMap<String, String>) {

        private fun conn(url: String, follow: Boolean): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = follow
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html, application/json, */*")
            if (jar.isNotEmpty()) {
                setRequestProperty("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
        }

        private fun finish(c: HttpURLConnection, resp: Resp): Resp {
            for (h in c.headerFields["Set-Cookie"].orEmpty()) {
                val pair = h.split(";").firstOrNull() ?: continue
                val i = pair.indexOf('=')
                if (i > 0) jar[pair.take(i).trim()] = pair.substring(i + 1).trim()
            }
            return resp
        }

        fun get(url: String, follow: Boolean = true): Resp {
            val c = conn(url, follow)
            c.requestMethod = "GET"
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = Resp(code, stream?.bufferedReader()?.readText().orEmpty(), c.getHeaderField("Location"), c.url.toString())
            finish(c, resp)
            c.disconnect()
            return resp
        }

        fun post(url: String, form: Map<String, String>): Resp {
            val c = conn(url, true)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val data = form.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
            }
            c.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = Resp(code, stream?.bufferedReader()?.readText().orEmpty(), c.getHeaderField("Location"), c.url.toString())
            finish(c, resp)
            c.disconnect()
            return resp
        }
    }

    /**
     * One full login; returns the JWT. Requires a trusted fingerprint (or a
     * TOTP secret for the second factor) on first use from a new device.
     */
    fun fetchJwt(user: String, password: String, totpSecret: String, fingerprint: String): String {
        require(user.isNotBlank() && password.isNotBlank()) { "统一身份账号或密码为空" }
        val jar = LinkedHashMap<String, String>()
        val http = Http(jar)
        // ① login form → SM2 public key
        val page = http.get(FORM_URL).body
        val pub = Regex("sm2publicKey\">([^<]+)<").find(page)?.groupValues?.get(1)?.trim()
            ?: throw IOException("统一认证登录表单解析失败（未找到 SM2 公钥）")
        // ② SM2(C1C3C2)-encrypted password login
        val ct = SmCrypto.encryptHex(pub, password.toByteArray(Charsets.UTF_8))
        var html = http.post(
            LOGIN_URL,
            mapOf(
                "i_user" to user,
                "i_pass" to ct,
                "fingerPrint" to fingerprint,
                "fingerGenPrint" to "",
                "i_captcha" to "",
            ),
        ).body
        // ③ second factor when challenged
        if (!html.contains("登录成功")) {
            if (html.contains("二次认证")) {
                html = twoFa(http, totpSecret, fingerprint)
            } else {
                val msg = Regex("id=\"msg_note\"[^>]*>([^<]+)<").find(html)?.groupValues?.get(1)?.trim()
                throw IOException("统一认证登录失败：${msg ?: "响应不含成功标记"}")
            }
        }
        // ④ ticket: scan the success page, then follow the callback chain
        val ticket = scanTicket(html)
            ?: grabTicket(http, parseCallback(html))
        // ⑤ direct madmodel check → JWT
        val body = http.get("$CHECK_URL?ticket=$ticket").body
        val jwt = try {
            JSONObject(body).optString("data")
        } catch (_: Throwable) {
            ""
        }
        if (jwt.isBlank()) throw IOException("check 未返回 key：${body.take(160)}")
        return jwt
    }

    // ---- 二次认证（TOTP 自动；指纹受信设备时根本不会进来） ----

    private fun doubleAuth(http: Http, form: Map<String, String>): JSONObject = try {
        JSONObject(http.post(DOUBLE_AUTH_URL, form).body)
    } catch (_: Throwable) {
        throw IOException("二次认证接口响应非 JSON")
    }

    private fun twoFa(http: Http, totpSecret: String, fingerprint: String): String {
        if (totpSecret.isBlank()) {
            throw IOException(
                "统一认证要求二次认证——请在下方填写 TOTP 密钥（自动通过），或改用已在 PC 端 madmodel_router 信任过的设备指纹",
            )
        }
        val r1 = doubleAuth(http, mapOf("action" to "FIND_APPROACHES"))
        val obj = r1.optJSONObject("object") ?: JSONObject()
        if (r1.optString("result") != "success" || !obj.optBoolean("hasTotp")) {
            throw IOException("二次认证查询失败或账号无 TOTP：${r1.optString("msg")}")
        }
        val snd = doubleAuth(http, mapOf("action" to "SEND_CODE", "type" to "totp"))
        if (snd.optString("result") != "success") {
            throw IOException("TOTP 发起失败：${snd.optString("msg")}")
        }
        val r3 = doubleAuth(http, mapOf("action" to "VERITY_TOTP_CODE", "vericode" to SmCrypto.totp(totpSecret)))
        if (r3.optString("result") != "success") {
            throw IOException("TOTP 校验失败：${r3.optString("msg")}")
        }
        val redirect = r3.optJSONObject("object")?.optString("redirectUrl").orEmpty()
        if (redirect.isBlank()) throw IOException("二次认证成功但缺少 redirectUrl")
        trustDevice(http, fingerprint)
        return http.get(if (redirect.startsWith("http")) redirect else "$ID_HOST/$redirect").body
    }

    private fun trustDevice(http: Http, fingerprint: String) {
        if (fingerprint.isBlank()) return
        runCatching {
            http.post(
                SAVE_FINGER_URL,
                mapOf(
                    "fingerprint" to fingerprint,
                    "deviceName" to "Kami-" + android.os.Build.MODEL,
                    "radioVal" to "是",
                ),
            )
        }
        // 失败不影响本次登录
    }

    // ---- ticket 抓取 ----

    private fun parseCallback(html: String): String? = Regex("<a[^>]+href=\"([^\"]+)\"").findAll(html)
        .map { it.groupValues[1] }
        .firstOrNull { !it.startsWith("/res/") && !it.startsWith("#") && !it.startsWith("javascript:") }

    private fun scanTicket(url: String): String? {
        val m = Regex("[?&#]ticket=([^&\\s'\"]+)").find(url) ?: return null
        return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
    }

    private fun grabTicket(http: Http, callback: String?): String {
        if (callback == null) throw IOException("登录成功页无跳转链接，未找到 ticket")
        var url = URL(URL(LOGIN_URL), callback).toString()
        repeat(12) {
            scanTicket(url)?.let { return it }
            val r = http.get(url, follow = false)
            val loc = r.location ?: return@repeat
            url = URL(URL(url), loc).toString()
            scanTicket(url)?.let { return it }
        }
        throw IOException("重定向链中未发现 ticket（终点 ${url.take(160)}）")
    }
}
