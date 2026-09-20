package com.kami.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Campus (Tsinghua) session store: the in-app WebView login (设置 → 校园账号)
 * seeds app cookies for learn.tsinghua.edu.cn / id.tsinghua.edu.cn; exporting
 * them here gives [LearnClient] a cookie session exactly like the WeClaude
 * browser-login export (session.json). No password is stored on the phone —
 * the learn plane is fully cookie-driven.
 */
object CampusStore {

    data class Session(
        val username: String,
        val savedAt: Long,
        val csrf: String,
        val learnCookies: String,
        val idCookies: String,
    )

    private const val FILE = "campus_session.json"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun file(): File = File(appContext.filesDir, FILE)

    fun get(): Session? {
        if (!::appContext.isInitialized || !file().exists()) return null
        return try {
            val o = JSONObject(file().readText())
            Session(
                username = o.optString("username"),
                savedAt = o.optLong("savedAt"),
                csrf = o.optString("csrf"),
                learnCookies = o.optString("learnCookies"),
                idCookies = o.optString("idCookies"),
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun save(s: Session) {
        file().writeText(
            JSONObject()
                .put("username", s.username)
                .put("savedAt", s.savedAt)
                .put("csrf", s.csrf)
                .put("learnCookies", s.learnCookies)
                .put("idCookies", s.idCookies)
                .toString(),
        )
    }

    fun clear() {
        if (::appContext.isInitialized) file().delete()
    }
}
