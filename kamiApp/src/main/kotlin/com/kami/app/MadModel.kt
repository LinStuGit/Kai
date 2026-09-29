package com.kami.app

import org.json.JSONObject
import java.util.Base64

/**
 * Fixed upstream facts for the in-app agent brain (mirrors the PC-side
 * madmodel_router.py). Since 2026-09 the gateway issues keys only through
 * the info-portal roaming chain (see [MadModelAuth]) and one account holds
 * a single live key — every fresh fetch invalidates the previous token.
 * A JWT IS the API key and expires after 6h — we refresh at 5h.
 */
object MadModel {
    const val BASE = "https://madmodel.cs.tsinghua.edu.cn/v1"

    /** Default gateway model; the selectable set lives in [ModelStore.MADMODEL_MODELS]. */
    const val MODEL = "DeepSeek-V4-Flash-0731"

    /** Server JWTs live 6h; refresh conservatively at 5h. */
    const val TTL_MS = 5L * 3600_000
}

/**
 * Single shared JWT: the gateway keeps only one live key per account, so
 * every agent session uses the same token from [MadModelAuth] and they all
 * move to a fresh one together on 401/403/409 or after [MadModel.TTL_MS].
 * (The old per-session pool with distinct tokens is impossible under the
 * one-live-key policy — fetching a second key would kill the first.)
 */
object JwtKeyPool {

    private class Entry(val key: String, val iat: Long?, val fetchedAt: Long)

    @Volatile
    private var entry: Entry? = null

    @Synchronized
    fun acquire(session: String): String {
        val now = System.currentTimeMillis()
        entry?.let { if (now - it.fetchedAt < MadModel.TTL_MS) return it.key }
        val key = MadModelAuth.fetchToken()
        entry = Entry(key, decodeIat(key), now)
        return key
    }

    /** 401/403/409: the account key died — every session refetches. */
    @Synchronized
    fun drop(session: String) {
        entry = null
    }

    @Synchronized
    fun dropAll() {
        entry = null
    }

    /** Single (session, iat, minutes until refresh) row for the settings card. */
    @Synchronized
    fun snapshot(): List<Triple<String, Long?, Long>> {
        val e = entry ?: return emptyList()
        val minutes = (MadModel.TTL_MS - (System.currentTimeMillis() - e.fetchedAt)) / 60_000
        return listOf(Triple("共享 key", e.iat, minutes))
    }

    fun decodeIat(jwt: String): Long? = runCatching {
        val payload = jwt.split(".")[1]
        val iat = JSONObject(String(Base64.getUrlDecoder().decode(payload))).optLong("iat", -1L)
        iat.takeIf { it >= 0 }
    }.getOrNull()
}
