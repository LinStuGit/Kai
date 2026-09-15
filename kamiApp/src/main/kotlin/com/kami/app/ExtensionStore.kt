package com.kami.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One agent-defined feature: a named shell command shown as a quick action. */
data class Extension(
    val id: String,
    val name: String,
    val desc: String,
    val cmd: String,
    val enabled: Boolean = true,
)

/**
 * Registry for the extension interface — features an agent adds over adb
 * (see [ExtensionReceiver]) or the user pastes in Settings.
 *
 * Backed by filesDir/extensions.json (+ a master switch in shared prefs);
 * exposed as Compose state so console chips and settings rows stay in sync.
 * Callers must [init] once per process (activity onCreate / receiver fire —
 * whichever wakes the process first).
 */
object ExtensionStore {

    val master = mutableStateOf(true)
    val items = mutableStateOf<List<Extension>>(emptyList())

    private var loaded = false
    private lateinit var file: File
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        file = File(app.filesDir, "extensions.json")
        prefs = app.getSharedPreferences("kami_ext", Context.MODE_PRIVATE)
        master.value = prefs.getBoolean("master", true)
        items.value = readDisk()
    }

    fun setMaster(value: Boolean) {
        master.value = value
        prefs.edit().putBoolean("master", value).apply()
    }

    /**
     * Add extensions from one `{…}` or `{"extensions":[…]}` JSON blob;
     * same-id entries are replaced. Returns how many were added.
     *
     * @throws org.json.JSONException on malformed input
     */
    fun addFromJson(json: String): Int {
        if (json.isBlank()) return 0
        val root = JSONObject(json)
        val arr = if (root.has("extensions")) {
            root.getJSONArray("extensions")
        } else {
            JSONArray().put(root)
        }
        val current = items.value.toMutableList()
        var added = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            val cmd = o.optString("cmd").trim()
            if (name.isEmpty() || cmd.isEmpty()) continue
            val ext = Extension(
                id = o.optString("id").ifBlank { "ext-" + name.hashCode() },
                name = name,
                desc = o.optString("desc"),
                cmd = cmd,
                enabled = o.optBoolean("enabled", true),
            )
            current.removeAll { it.id == ext.id }
            current.add(ext)
            added++
        }
        items.value = current
        save()
        return added
    }

    fun remove(id: String) {
        items.value = items.value.filterNot { it.id == id }
        save()
    }

    fun setEnabled(id: String, value: Boolean) {
        items.value = items.value.map {
            if (it.id == id) it.copy(enabled = value) else it
        }
        save()
    }

    fun clear() {
        items.value = emptyList()
        save()
    }

    fun toJson(): String {
        val arr = JSONArray()
        items.value.forEach { arr.put(toJson(it)) }
        return arr.toString()
    }

    private fun toJson(e: Extension): JSONObject = JSONObject()
        .put("id", e.id)
        .put("name", e.name)
        .put("desc", e.desc)
        .put("cmd", e.cmd)
        .put("enabled", e.enabled)

    private fun readDisk(): List<Extension> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Extension(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    desc = o.optString("desc"),
                    cmd = o.optString("cmd"),
                    enabled = o.optBoolean("enabled", true),
                )
            }.filter { it.name.isNotEmpty() && it.cmd.isNotEmpty() }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun save() {
        val arr = JSONArray()
        items.value.forEach { arr.put(toJson(it)) }
        file.writeText(arr.toString(2))
    }
}
