package com.kami.app

import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray

/** One rendered chat line; role is "user", "assistant" or "event". */
internal data class ChatLine(val role: String, val text: String)

internal const val WELCOME =
    "我是 Kami agent——可以直接操作这台手机（Shizuku shell），也能在 Alpine Linux " +
        "沙箱里跑命令，把常用操作固化为快捷指令。" +
        "试试：「看看设备信息」「装好沙箱后帮我在里面装 curl」或「加一个一键截屏功能」"

/** One parallel agent session: its own chat history and its own JWT key. */
internal data class AgentSession(
    val id: String,
    val title: String,
    val history: JSONArray = JSONArray(),
    val lines: List<ChatLine> = listOf(ChatLine("assistant", WELCOME)),
    val busy: Boolean = false,
)

/**
 * In-memory multi-session registry: sessions run in parallel (each turn is
 * its own coroutine) and each maps to a distinct JWT via JwtKeyPool keyed
 * "chat:<id>". Lifetime matches the previous single-history behavior:
 * alive across navigation, cleared when the process dies.
 */
internal object SessionStore {

    val sessions = mutableStateOf(listOf(newSession("s1", 1)))
    val activeId = mutableStateOf("s1")

    private var seq = 1

    val active: AgentSession?
        get() = sessions.value.firstOrNull { it.id == activeId.value }

    fun newSession(): String {
        seq += 1
        val s = newSession("s$seq", seq)
        sessions.value = sessions.value + s
        activeId.value = s.id
        return s.id
    }

    fun remove(id: String) {
        val rest = sessions.value.filterNot { it.id == id }
        val next = rest.ifEmpty { listOf(newSession("s${++seq}", seq)) }
        sessions.value = next
        if (activeId.value == id) activeId.value = next.first().id
        JwtKeyPool.drop("chat:$id")
    }

    fun update(id: String, transform: (AgentSession) -> AgentSession) {
        sessions.value = sessions.value.map { if (it.id == id) transform(it) else it }
    }

    fun history(id: String): JSONArray =
        sessions.value.firstOrNull { it.id == id }?.history ?: JSONArray()

    private fun newSession(id: String, n: Int) = AgentSession(id = id, title = "会话 $n")
}
