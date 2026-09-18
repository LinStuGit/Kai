package com.kami.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finished conversations kept on disk instead of dropped: removing a
 * session archives it here, and so do sessions left over when the process
 * died (SessionStore persists live, this store survives).
 */
internal object ArchiveStore {

    data class ArchivedSession(
        val id: String,
        val title: String,
        val ts: Long,
        val lines: List<ChatLine>,
    )

    val items = mutableStateOf<List<ArchivedSession>>(emptyList())

    private lateinit var file: File

    /** Call once at app start, after [SessionStore.init]. */
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "kami_archive.json")
        load()
        // Sessions persisted by a previous process never got removed
        // properly — archive them now.
        SessionStore.drainPersisted().forEach { (title, lines) -> archive(title, lines) }
    }

    /** Archive one conversation; welcome-only sessions are dropped. */
    fun archive(title: String, lines: List<ChatLine>, ts: Long = System.currentTimeMillis()) {
        if (lines.size <= 1) return
        items.value = listOf(ArchivedSession("a$ts-$title".hashCode().toString(), title, ts, lines)) +
            items.value
        if (items.value.size > 50) items.value = items.value.take(50)
        persist()
    }

    fun remove(id: String) {
        items.value = items.value.filterNot { it.id == id }
        persist()
    }

    fun clear() {
        items.value = emptyList()
        persist()
    }

    private fun load() {
        if (!::file.isInitialized || !file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            items.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ArchivedSession(
                    o.getString("id"),
                    o.getString("title"),
                    o.optLong("ts"),
                    SessionStore.chatLinesFrom(o.optJSONArray("lines")),
                )
            }
        }
    }

    private fun persist() {
        if (!::file.isInitialized) return
        runCatching {
            val arr = JSONArray()
            items.value.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("id", a.id)
                        .put("title", a.title)
                        .put("ts", a.ts)
                        .put("lines", SessionStore.chatLinesTo(a.lines)),
                )
            }
            file.writeText(arr.toString())
        }
    }
}
