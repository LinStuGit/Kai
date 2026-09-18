package com.kami.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One rendered chat line; role is "user", "assistant" or "event".
 *  [thinking] (model reasoning) rides on assistant lines, rendered collapsed. */
internal data class ChatLine(val role: String, val text: String, val thinking: String? = null)

internal const val WELCOME =
    "我是 Kami agent——可以直接操作这台手机（Shizuku shell），也能在 Alpine Linux " +
        "沙箱里跑命令，联网搜索、定时任务、日历日程都在手边。" +
        "试试：「看看设备信息」「搜一下今天的新闻」「明早 7 点提醒我早读」或「在沙箱里装 curl」"

/** One parallel agent session: its own chat history and its own JWT key. */
internal data class AgentSession(
    val id: String,
    val title: String,
    val history: JSONArray = JSONArray(),
    val lines: List<ChatLine> = listOf(ChatLine("assistant", WELCOME)),
    val busy: Boolean = false,
)

/**
 * Multi-session registry: sessions run in parallel (each turn is its own
 * coroutine) and each maps to a distinct JWT via JwtKeyPool keyed
 * "chat:<id>". Sessions persist to disk live so a dead process hands them
 * to [ArchiveStore] on the next start instead of losing them.
 */
internal object SessionStore {

    val sessions = mutableStateOf(listOf(newSession("s1", 1)))
    val activeId = mutableStateOf("s1")

    private var seq = 1
    private var file: File? = null

    val active: AgentSession?
        get() = sessions.value.firstOrNull { it.id == activeId.value }

    /** Call once at app start, before [ArchiveStore.init]. */
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "kami_sessions.json")
    }

    /** Sessions persisted by a previous process; deletes the store file. */
    fun drainPersisted(): List<Pair<String, List<ChatLine>>> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        val out = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("title") to chatLinesFrom(o.optJSONArray("lines"))
            }
        }.getOrElse { emptyList() }
        f.delete()
        return out
    }

    fun newSession(): String {
        seq += 1
        val s = newSession("s$seq", seq)
        sessions.value = sessions.value + s
        activeId.value = s.id
        persist()
        return s.id
    }

    fun remove(id: String) {
        sessions.value.firstOrNull { it.id == id }?.let {
            ArchiveStore.archive(it.title, it.lines)
        }
        val rest = sessions.value.filterNot { it.id == id }
        val next = rest.ifEmpty { listOf(newSession("s${++seq}", seq)) }
        sessions.value = next
        if (activeId.value == id) activeId.value = next.first().id
        JwtKeyPool.drop("chat:$id")
        persist()
    }

    fun update(id: String, transform: (AgentSession) -> AgentSession) {
        sessions.value = sessions.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    fun history(id: String): JSONArray = sessions.value.firstOrNull { it.id == id }?.history ?: JSONArray()

    internal fun chatLinesTo(lines: List<ChatLine>): JSONArray = JSONArray().apply {
        lines.forEach { l ->
            put(
                JSONObject()
                    .put("role", l.role)
                    .put("text", l.text)
                    .put("thinking", l.thinking ?: JSONObject.NULL),
            )
        }
    }

    internal fun chatLinesFrom(arr: JSONArray?): List<ChatLine> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val thinking = o.optString("thinking", "")
            ChatLine(o.getString("role"), o.getString("text"), thinking.ifEmpty { null })
        }
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            sessions.value.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("lines", chatLinesTo(s.lines)),
                )
            }
            f.writeText(arr.toString())
        }
    }

    private fun newSession(id: String, n: Int) = AgentSession(id = id, title = "会话 $n")
}
