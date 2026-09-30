package com.kami.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Direct id.tsinghua.edu.cn (old do/off IdP) login — Kotlin port of the PC
 * madmodel_router.py flow. One credential entry now serves two consumers:
 *
 * - madmodel: app form md5("DEEPSEEK") → SM2 password POST → ticket →
 *   model-api/auth-login/check → JWT（一账号同时只有一个存活 key）.
 * - learn (网络学堂): app form bb5df852… → ticket →
 *   b/j_spring_security_thauth_roaming_entry seeds the learn session — the
 *   headless replacement of the WebView CAS login (thu-learn-lib 同链路).
 *
 * The second app usually rides the CAS SSO session: its form request 302s
 * straight to a fresh ticket, no second password POST.
 *
 * 二次认证: a trusted device fingerprint skips it entirely (saveFinger runs
 * after every success). Otherwise a stored TOTP secret auto-passes; interactive
 * verification (企业微信/短信验证码/手输动态码) goes through [TwoFaGate].
 */
internal object IdpLogin {

    private const val ID_HOST = "https://id.tsinghua.edu.cn"
    private const val APP_ID = "DEEPSEEK"
    private val APP_MD5 = MessageDigest.getInstance("MD5")
        .digest(APP_ID.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }
    private val FORM_URL = "$ID_HOST/do/off/ui/auth/login/form/$APP_MD5/0?/authLogin"

    /** 网络学堂的 CAS app id（thu-learn-lib ID_LOGIN 同款 md5）。 */
    private const val LEARN_APP_MD5 = "bb5df85216504820be7bba2b0ae1535b"
    private val LEARN_FORM_URL = "$ID_HOST/do/off/ui/auth/login/form/$LEARN_APP_MD5/0"

    private const val LEARN_HOST = "https://learn.tsinghua.edu.cn"
    private const val LEARN_ROAM_URL = "$LEARN_HOST/b/j_spring_security_thauth_roaming_entry?ticket="
    private const val LEARN_COURSE_PAGE = "$LEARN_HOST/f/wlxt/index/course/student/"

    private const val LOGIN_URL = "$ID_HOST/do/off/ui/auth/login/check"
    private const val LOGIN_CHECK_SINGLE_URL = "$ID_HOST/do/off/ui/auth/login/checkSingle"
    private const val DOUBLE_AUTH_URL = "$ID_HOST/b/doubleAuth/login"
    private const val SAVE_FINGER_URL = "$ID_HOST/b/doubleAuth/personal/saveFinger"

    private val CHECK_URL =
        MadModel.BASE.removeSuffix("/v1") + "/model-api/auth-login/check"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    /** Interactive second-factor hook — the login thread blocks until answered. */
    internal interface TwoFaGate {
        /** 请用户选验证方式；返回所选（"wechat"/"sms"/"totp"），null = 取消。 */
        fun chooseMethod(available: List<String>): String?

        /** 验证码已发到 method（err = 上次校验失败原因）；阻塞等输入，null = 取消。 */
        fun enterCode(method: String, err: String?): String?
    }

    internal fun label(method: String): String = when (method) {
        "wechat" -> "企业微信"
        "sms" -> "短信"
        else -> "动态口令"
    }

    /** loginAll 的产出：jwt 必有；learn 部分失败不拦 key（learnError 说明原因）。 */
    internal class Unified(
        val jwt: String,
        val username: String,
        val learnCookies: String,
        val idCookies: String,
        val csrf: String,
        val learnError: String,
    )

    private class Resp(val code: Int, val body: String, val location: String?, val finalUrl: String)

    /** Host-scoped cookie jar（id/learn 各自的 JSESSIONID 不串）+ 手动重定向链。 */
    private class Http(private val jar: MutableMap<String, MutableMap<String, String>>) {

        private fun host(url: String) = URL(url).host

        private fun cookieHeader(url: String): String? = jar[host(url)]
            ?.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

        private fun absorb(c: HttpURLConnection, url: String) {
            val m = jar.getOrPut(host(url)) { LinkedHashMap() }
            for (h in c.headerFields["Set-Cookie"].orEmpty()) {
                val pair = h.split(";").firstOrNull() ?: continue
                val i = pair.indexOf('=')
                if (i > 0) m[pair.take(i).trim()] = pair.substring(i + 1).trim()
            }
        }

        private fun conn(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // 手动跟重定向：自动跟会丢掉中间跳的 Set-Cookie（learn 会话就种不上）
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html, application/json, */*")
            cookieHeader(url)?.let { setRequestProperty("Cookie", it) }
        }

        private fun read(c: HttpURLConnection, url: String): Resp {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = Resp(code, stream?.bufferedReader()?.readText().orEmpty(), c.getHeaderField("Location"), url)
            absorb(c, url)
            c.disconnect()
            return resp
        }

        private fun rawGet(url: String): Resp {
            val c = conn(url)
            c.requestMethod = "GET"
            return read(c, url)
        }

        private fun rawPost(url: String, form: Map<String, String>): Resp {
            val c = conn(url)
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val data = form.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
            }
            c.outputStream.use { it.write(data.toByteArray(Charsets.UTF_8)) }
            return read(c, url)
        }

        /** GET，自动跟 ≤12 跳，每跳收 Set-Cookie。 */
        fun get(url: String): Resp {
            var u = url
            repeat(12) {
                val r = rawGet(u)
                val loc = r.location ?: return r
                u = URL(URL(u), loc).toString()
            }
            return rawGet(u)
        }

        /** 手动跟链用（每跳查 ticket，ticket 在被消费前拦下）。 */
        fun getNoFollow(url: String): Resp = rawGet(url)

        /** 单跳 POST（IdP check/doubleAuth 都以 200 页面应答，router 同款不跟 3xx）。 */
        fun post(url: String, form: Map<String, String>): Resp = rawPost(url, form)

        fun snapshot(host: String): String =
            jar[host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: ""
    }

    // ---- 入口 ----

    /** Silent reauth（JwtKeyPool 后台续期）：无 UI gate，受信指纹/TOTP 自动过。 */
    fun fetchJwt(user: String, password: String, totpSecret: String, fingerprint: String): String {
        require(user.isNotBlank() && password.isNotBlank()) { "统一身份账号或密码为空" }
        val http = Http(LinkedHashMap())
        val ticket = loginApp(http, FORM_URL, false, user, password, totpSecret, fingerprint, gate = null)
        return exchangeJwt(http, ticket)
    }

    /** One credential entry → learn session + madmodel JWT。learn 失败不拦 key。 */
    fun loginAll(
        user: String,
        password: String,
        totpSecret: String,
        fingerprint: String,
        gate: TwoFaGate?,
    ): Unified {
        require(user.isNotBlank() && password.isNotBlank()) { "统一身份账号或密码为空" }
        val http = Http(LinkedHashMap())
        // ① madmodel（已验证的主链路）：登录建立 IdP CAS 会话
        val jwt = exchangeJwt(http, loginApp(http, FORM_URL, false, user, password, totpSecret, fingerprint, gate))
        // ② learn：CAS 会话已在，表单多半直接 SSO 放票；失败不影响 key
        var learnCookies = ""
        var csrf = ""
        var learnError = ""
        try {
            val ticket = loginApp(http, LEARN_FORM_URL, true, user, password, totpSecret, fingerprint, gate)
            http.get(LEARN_ROAM_URL + ticket)
            learnCookies = http.snapshot("learn.tsinghua.edu.cn")
            if (learnCookies.isBlank()) throw IOException("roaming 未种下 learn 会话")
            csrf = Regex("&_csrf=(\\S*)\"").find(http.get(LEARN_COURSE_PAGE).body)?.groupValues?.get(1).orEmpty()
        } catch (t: Throwable) {
            learnError = t.message ?: "learn 登录失败"
        }
        return Unified(jwt, user, learnCookies, http.snapshot("id.tsinghua.edu.cn"), csrf, learnError)
    }

    private fun exchangeJwt(http: Http, ticket: String): String {
        val body = http.get("$CHECK_URL?ticket=$ticket").body
        val jwt = try {
            JSONObject(body).optString("data")
        } catch (_: Throwable) {
            ""
        }
        if (jwt.isBlank()) throw IOException("check 未返回 key：${body.take(160)}")
        return jwt
    }

    /** 一次完整 app 登录（表单→密码 POST→二次认证→ticket）。 */
    private fun loginApp(
        http: Http,
        formUrl: String,
        learnStyle: Boolean,
        user: String,
        password: String,
        totpSecret: String,
        fingerprint: String,
        gate: TwoFaGate?,
    ): String {
        // CAS SSO：已登录态下表单请求直接 302 放票（第二个 app 通常走这里）
        ssoTicket(http, formUrl)?.let { return it }
        val page = http.get(formUrl).body
        val pub = Regex("sm2publicKey\">([^<]+)<").find(page)?.groupValues?.get(1)?.trim()
            ?: throw IOException("登录表单解析失败（未找到 SM2 公钥）")
        val ct = SmCrypto.encryptHex(pub, password.toByteArray(Charsets.UTF_8))
        // 受信设备上 learn 表单会带 checkSingle（免密单点）——先试，不行再走密码
        if (learnStyle && page.contains("checkSingle")) {
            val r = http.post(
                LOGIN_CHECK_SINGLE_URL,
                linkedMapOf(
                    "i_rememberme" to "on",
                    "fingerPrint" to fingerprint,
                    "fingerGenPrint" to "",
                ),
            )
            fromResponse(http, r, totpSecret, fingerprint, gate, soft = true)?.let { return it }
        }
        val fields = linkedMapOf(
            "i_user" to user,
            "i_pass" to ct,
        )
        if (learnStyle) {
            // thu-learn-lib 同款 learn 字段
            fields["singleLogin"] = "on"
            fields["fingerPrint"] = fingerprint
            fields["fingerGenPrint"] = ""
            fields["fingerGenPrint3"] = ""
            fields["i_captcha"] = ""
        } else {
            fields["fingerPrint"] = fingerprint
            fields["fingerGenPrint"] = ""
            fields["i_captcha"] = ""
        }
        val r = http.post(LOGIN_URL, fields)
        return fromResponse(http, r, totpSecret, fingerprint, gate, soft = false)
            ?: throw IOException("统一认证登录失败：响应不含成功标记")
    }

    /** 登录应答 → ticket；soft = true 时失败返回 null（供免密路径回退）。 */
    private fun fromResponse(
        http: Http,
        r: Resp,
        totpSecret: String,
        fingerprint: String,
        gate: TwoFaGate?,
        soft: Boolean,
    ): String? {
        if (r.code in 300..399) {
            val loc = r.location ?: return null
            return scanTicket(loc) ?: grabTicket(http, loc)
        }
        var html = r.body
        if (html.contains("二次认证")) {
            html = twoFa(http, totpSecret, fingerprint, gate)
        } else if (!html.contains("登录成功")) {
            if (soft) return null
            // learn-lib 同款坏凭据标记：c_note 连弹多次
            val bad = html.split("$(\"#c_note\").show();").size - 1 > 3
            val msg = Regex("id=\"msg_note\"[^>]*>([^<]+)<").find(html)?.groupValues?.get(1)?.trim()
                ?: if (bad) "账号或密码错误" else "响应不含成功标记（HTTP ${r.code}）"
            throw IOException("统一认证登录失败：$msg")
        }
        return scanTicket(html) ?: grabTicket(http, parseCallback(html))
    }

    /** 已登录 CAS 的放票探测：沿表单请求的跳向每跳查 ticket（不真进 service，ticket 单次有效）。 */
    private fun ssoTicket(http: Http, formUrl: String): String? {
        var url = formUrl
        repeat(12) {
            scanTicket(url)?.let { return it }
            val r = http.getNoFollow(url)
            scanTicket(r.body)?.let { return it }
            val loc = r.location ?: return null
            url = URL(URL(url), loc).toString()
        }
        return null
    }

    // ---- 二次认证 ----

    private class VerifyFail(msg: String) : IOException(msg)

    private fun doubleAuth(http: Http, form: Map<String, String>): JSONObject = try {
        JSONObject(http.post(DOUBLE_AUTH_URL, form).body)
    } catch (_: Throwable) {
        throw IOException("二次认证接口响应非 JSON")
    }

    private fun twoFa(http: Http, totpSecret: String, fingerprint: String, gate: TwoFaGate?): String {
        val r1 = doubleAuth(http, mapOf("action" to "FIND_APPROACHES"))
        val obj = r1.optJSONObject("object") ?: JSONObject()
        if (r1.optString("result") != "success") {
            throw IOException("二次认证查询失败：${r1.optString("msg")}")
        }
        val hasTotp = obj.optBoolean("hasTotp")
        val methods = buildList {
            if (hasTotp) add("totp")
            if (obj.optBoolean("hasWeChatBool")) add("wechat")
            if (obj.optString("phone").isNotBlank()) add("sms")
        }
        if (methods.isEmpty()) throw IOException("账号未绑定任何二次认证方式（TOTP/企业微信/短信）")
        // 存了 TOTP 密钥就先自动过（不打扰用户）；失败（如时钟漂移）落回交互
        if (hasTotp && totpSecret.isNotBlank()) {
            try {
                return land(http, "totp", SmCrypto.totp(totpSecret), fingerprint)
            } catch (e: VerifyFail) {
                if (gate == null) throw IOException("TOTP 自动校验失败：${e.message}")
            }
        }
        val g = gate ?: throw IOException(
            "统一认证要求二次认证——填写 TOTP 密钥可自动通过，或在设置页登录时选择企业微信/短信验证码",
        )
        val interactive = methods.filter { it != "totp" }.ifEmpty { methods }
        var method = g.chooseMethod(interactive) ?: throw IOException("二次认证已取消")
        var err: String? = null
        repeat(3) {
            val snd = doubleAuth(http, mapOf("action" to "SEND_CODE", "type" to method))
            if (snd.optString("result") != "success") {
                throw IOException("验证码发送失败（${label(method)}）：${snd.optString("msg")}")
            }
            val code = g.enterCode(method, err) ?: throw IOException("二次认证已取消")
            try {
                return land(http, method, code.trim(), fingerprint)
            } catch (e: VerifyFail) {
                err = e.message
            }
        }
        throw IOException("二次认证连续失败：$err")
    }

    /** 校验验证码并落地：跟 redirectUrl 回成功页；顺手信任本设备指纹。 */
    private fun land(http: Http, method: String, code: String, fingerprint: String): String {
        val action = if (method == "totp") "VERITY_TOTP_CODE" else "VERITY_CODE"
        val r = doubleAuth(http, mapOf("action" to action, "vericode" to code))
        if (r.optString("result") != "success") {
            throw VerifyFail(r.optString("msg").ifBlank { "校验失败" })
        }
        val redirect = r.optJSONObject("object")?.optString("redirectUrl").orEmpty()
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

    private fun chaseTicket(http: Http, callback: String): String? {
        var url = URL(URL(LOGIN_URL), callback).toString()
        repeat(12) {
            scanTicket(url)?.let { return it }
            val r = http.getNoFollow(url)
            val loc = r.location ?: return null
            url = URL(URL(url), loc).toString()
            scanTicket(url)?.let { return it }
        }
        return null
    }

    private fun grabTicket(http: Http, callback: String?): String =
        callback?.let { chaseTicket(http, it) }
            ?: throw IOException("登录成功页无跳转链接，未找到 ticket")
}
