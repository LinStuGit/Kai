package com.kami.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Fixed upstream facts for the in-app agent brain (mirrors the PC-side
 * madmodel_router.py): the check endpoint hands out JWTs with no
 * credentials, a JWT IS the API key, the server reuses one token per
 * signing second, and tokens expire after 6h — we refresh at 5h.
 */
object MadModel {
    const val BASE = "https://madmodel.cs.tsinghua.edu.cn/v1"
    const val CHECK_URL = "https://madmodel.cs.tsinghua.edu.cn/model-api/auth-login/check"
    const val MODEL = "DeepSeek-V4-Flash-0731"

    /** Server JWTs live 6h; refresh conservatively at 5h. */
    const val TTL_MS = 5L * 3600_000
}

/**
 * Per-session JWT key pool: every agent session acquires its own token
 * from [MadModel.CHECK_URL]; tokens inside the pool are kept distinct
 * (same-second issuance reuses the same iat — sleep past the window) and
 * are refreshed after [MadModel.TTL_MS]. 401/403/409 responses drop the
 * session token so the next call refetches.
 */
object JwtKeyPool {

    private const val MAX_FETCH_TRIES = 5

    private class Entry(val key: String, val iat: Long?, val fetchedAt: Long)

    private val pool = HashMap<String, Entry>()

    @Synchronized
    fun acquire(session: String): String {
        val now = System.currentTimeMillis()
        pool[session]?.let { if (now - it.fetchedAt < MadModel.TTL_MS) return it.key }
        val key = fetchDistinct()
        pool[session] = Entry(key, decodeIat(key), now)
        return key
    }

    @Synchronized
    fun drop(session: String) {
        pool.remove(session)
    }

    @Synchronized
    fun dropAll() {
        pool.clear()
    }

    /** (session, iat, minutes until refresh) rows for the settings card. */
    @Synchronized
    fun snapshot(): List<Triple<String, Long?, Long>> {
        val now = System.currentTimeMillis()
        return pool.map { (session, entry) ->
            Triple(session, entry.iat, (MadModel.TTL_MS - (now - entry.fetchedAt)) / 60_000)
        }.sortedBy { it.first }
    }

    fun decodeIat(jwt: String): Long? = runCatching {
        val payload = jwt.split(".")[1]
        val iat = JSONObject(String(Base64.getUrlDecoder().decode(payload))).optLong("iat", -1L)
        iat.takeIf { it >= 0 }
    }.getOrNull()

    /** GET the check endpoint — `{"data": "<jwt>", …}`, no credentials. */
    private fun fetch(): String {
        val conn = URL(MadModel.CHECK_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/json")
            val text = conn.inputStream.bufferedReader().readText()
            if (conn.responseCode !in 200..299) {
                throw IOException("check HTTP ${conn.responseCode}: ${text.take(200)}")
            }
            val key = JSONObject(text).optString("data")
            if (key.isBlank()) throw IOException("check 端点缺少 data 字段")
            return key
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The server signs one JWT per second: a fetch within the same second
     * returns an identical iat. Sleep 1.6s and refetch until the new token
     * differs from every pooled one, so parallel sessions never share a key.
     */
    private fun fetchDistinct(): String {
        var key = fetch()
        repeat(MAX_FETCH_TRIES - 1) {
            val iat = decodeIat(key) ?: return key
            val clash = synchronized(this) { pool.values.any { it.iat == iat } }
            if (!clash) return key
            try {
                Thread.sleep(1_600)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return key
            }
            key = fetch()
        }
        return key // extreme case: accept a duplicate rather than fail
    }
}
