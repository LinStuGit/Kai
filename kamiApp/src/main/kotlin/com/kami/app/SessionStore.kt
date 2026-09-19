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

    val sessions = mutableStateOf(listOf(newSession("s1")))
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
    fun drainPersisted(): List<Triple<String, List<ChatLine>, JSONArray?>> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        val out = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Triple(
                    o.getString("title"),
                    chatLinesFrom(o.optJSONArray("lines")),
                    o.optJSONArray("history"),
                )
            }
        }.getOrElse { emptyList() }
        f.delete()
        return out
    }

    fun newSession(): String {
        seq += 1
        val s = newSession("s$seq")
        sessions.value = sessions.value + s
        activeId.value = s.id
        persist()
        return s.id
    }

    /**
     * Short conversations get a summary name from their content — the
     * first user message — instead of a numbered "会话 N". Nothing to
     * summarize before the first message, so a fresh session stays
     * "新会话" until [retitlePlaceholder] renames it.
     */
    fun summarize(text: String, max: Int = 14): String {
        var t = text.replace(Regex("\\s+"), " ")
            .trim('？', '！', '。', '?', '!', '，', ',', '、', '.', '；', ';', ' ')
        if (t.length > max) t = t.substring(0, max).trim() + "…"
        return t.ifEmpty { "对话" }
    }

    /** Rename the session to its first-message summary if it's still unnamed. */
    fun retitlePlaceholder(id: String, text: String) {
        val s = sessions.value.firstOrNull { it.id == id } ?: return
        if (s.title == "新会话") {
            update(id) { it.copy(title = summarize(text)) }
        }
    }

    fun remove(id: String) {
        sessions.value.firstOrNull { it.id == id }?.let {
            ArchiveStore.archive(it.title, it.lines, history = it.history)
        }
        val rest = sessions.value.filterNot { it.id == id }
        val next = rest.ifEmpty { listOf(newSession("s${++seq}")) }
        sessions.value = next
        if (activeId.value == id) activeId.value = next.first().id
        JwtKeyPool.drop("chat:$id")
        persist()
    }

    /**
     * Bring an archived conversation back as a fresh active session —
     * transcript lines and (when archived with it) the API history, so the
     * agent keeps its context and the chat continues where it left off.
     */
    fun restore(title: String, lines: List<ChatLine>, history: JSONArray?): String {
        seq += 1
        val id = "s$seq"
        val hist = history?.let { h -> runCatching { JSONArray(h.toString()) }.getOrNull() }
            ?: historyFromLines(lines)
        // Restored conversations are re-named from their own content so the
        // history list never fills with duplicates.
        val restored = lines.asSequence().firstOrNull { it.role == "user" }?.text
            ?.let { summarize(it) }
            ?: title.takeIf { it.isNotBlank() && it != "恢复的会话" && !it.startsWith("会话 ") }
            ?: "对话"
        sessions.value = sessions.value + AgentSession(
            id = id,
            title = restored,
            history = hist,
            lines = lines,
        )
        activeId.value = id
        persist()
        return id
    }

    /** Fallback for old archives that were saved without API history. */
    private fun historyFromLines(lines: List<ChatLine>): JSONArray {
        val arr = JSONArray()
        lines.forEach { l ->
            when (l.role) {
                "user" -> arr.put(JSONObject().put("role", "user").put("content", l.text))

                "assistant" -> if (l.text != WELCOME) {
                    arr.put(JSONObject().put("role", "assistant").put("content", l.text))
                }
            }
        }
        return arr
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
                        .put("history", s.history)
                        .put("lines", chatLinesTo(s.lines)),
                )
            }
            f.writeText(arr.toString())
        }
    }

    private fun newSession(id: String) = AgentSession(id = id, title = "新会话")
}
