package com.kami.app

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Skills: named prompt templates ({{variable}} placeholders) the user or
 * the agent can register; skill_run expands one into a full task
 * instruction. Persisted to filesDir/skills.json.
 */
data class Skill(val id: String, val name: String, val desc: String, val template: String)

object SkillStore {

    private val items = mutableListOf<Skill>()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "skills.json")
        items.clear()
        runCatching { fromJson(file!!.readText()) }
    }

    /** Import one object or an array of them; returns how many were added. */
    fun addFromJson(json: String): Int {
        val arr = runCatching { JSONArray(json) }.getOrElse {
            JSONArray("[$json]")
        }
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            val template = o.optString("template").trim()
            if (name.isEmpty() || template.isEmpty()) continue
            val skill = Skill(
                id = o.optString("id").ifBlank { "skill-" + System.currentTimeMillis() + "-$added" },
                name = name,
                desc = o.optString("desc").trim(),
                template = template,
            )
            items.removeAll { it.id == skill.id || it.name == skill.name }
            items.add(skill)
            added++
        }
        if (added > 0) persist()
        return added
    }

    fun remove(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    fun all(): List<Skill> = items.toList()

    fun byName(name: String): Skill? = items.firstOrNull { it.name == name }

    /** Expand {{key}} placeholders with [args]. */
    fun fill(skill: Skill, args: JSONObject): String {
        var out = skill.template
        val keys = args.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out = out.replace("{{$k}}", args.optString(k))
        }
        return out
    }

    private fun fromJson(json: String) {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items.add(
                Skill(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    desc = o.optString("desc"),
                    template = o.getString("template"),
                ),
            )
        }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("id", it.id)
                        .put("name", it.name)
                        .put("desc", it.desc)
                        .put("template", it.template),
                )
            }
            file!!.writeText(arr.toString())
        }
    }
}

/**
 * Extension point for tools that live outside the built-in set — MCP
 * servers, plugins, future in-app transports. The agent request merges
 * registered schemas into the tools array and unknown tool names fall
 * back to the registry, so a future MCP client only has to adapt server
 * tools into [ExtraTool] instances (streamable-HTTP transport; not shipped
 * yet — the interface is the contract).
 */
interface ExtraTool {
    val name: String
    val description: String

    /** JSON-schema `{"type":"function","function":{...}}` for the request. */
    fun schema(): JSONObject

    fun execute(argsJson: String): String
}

object ExtraToolRegistry {

    private val tools = ConcurrentHashMap<String, ExtraTool>()

    fun register(tool: ExtraTool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): ExtraTool? = tools[name]

    fun all(): List<ExtraTool> = tools.values.toList()

    fun schemas(): JSONArray {
        val arr = JSONArray()
        tools.values.forEach { arr.put(it.schema()) }
        return arr
    }
}
