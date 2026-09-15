package com.inspiredandroid.kai.tools

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One agent-defined feature: a named host shell command, runnable by id. */
@Serializable
data class Extension(
    val id: String,
    val name: String,
    val desc: String = "",
    val cmd: String,
    val enabled: Boolean = true,
)

/**
 * Registry of agent-registered features, backed by filesDir/extensions.json.
 * The agent discovers entries via [ShizukuTools.listExtensionsTool] and runs
 * them by id; entries survive process death. Call [init] once per process
 * before use (getAvailableTools does it from the injected Context).
 */
object ExtensionStore {

    @Serializable
    private data class Snapshot(val items: List<Extension> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "extensions.json")
    }

    @Synchronized
    fun all(): List<Extension> = read()

    @Synchronized
    fun add(ext: Extension): Extension {
        write(read().filterNot { it.id == ext.id } + ext)
        return ext
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val items = read()
        val rest = items.filterNot { it.id == id }
        if (rest.size == items.size) return false
        write(rest)
        return true
    }

    @Synchronized
    fun get(id: String): Extension? = read().firstOrNull { it.id == id }

    private fun read(): List<Extension> = try {
        val f = file ?: return emptyList()
        if (f.exists()) {
            json.decodeFromString(Snapshot.serializer(), f.readText()).items
        } else {
            emptyList()
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun write(items: List<Extension>) {
        try {
            file?.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(items)))
        } catch (_: Throwable) {
        }
    }
}
