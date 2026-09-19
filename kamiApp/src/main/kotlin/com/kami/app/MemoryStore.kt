package com.kami.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import java.io.File

/**
 * Cross-session memory the agent curates itself: important facts the user
 * mentions (preferences, project background, standing agreements) are
 * persisted to kami_memory.json and injected into every turn's system
 * prompt. Also readable back via the memory_recall tool.
 */
object MemoryStore {

    private const val CAP = 200

    // Snapshot-backed so the management UI recomposes on every change.
    private val mem = mutableStateListOf<String>()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "kami_memory.json")
        mem.clear()
        runCatching {
            val arr = JSONArray(file!!.readText())
            for (i in 0 until arr.length()) mem.add(arr.getString(i))
        }
    }

    /** Save a fact; exact duplicates are dropped, oldest evicted at cap. */
    fun save(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (mem.any { it == t }) return false
        mem.add(t)
        while (mem.size > CAP) mem.removeAt(0)
        persist()
        return true
    }

    fun all(): List<String> = mem.toList()

    fun recent(n: Int): List<String> = mem.takeLast(n)

    fun search(query: String): List<String> = mem.filter { it.contains(query, ignoreCase = true) }

    fun remove(text: String): Boolean {
        val removed = mem.removeAll { it == text }
        if (removed) persist()
        return removed
    }

    fun clear() {
        mem.clear()
        persist()
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            mem.forEach { arr.put(it) }
            file!!.writeText(arr.toString())
        }
    }
}
