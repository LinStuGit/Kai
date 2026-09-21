package com.kami.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** A resolved attachment: text files keep decoded content, images a base64 payload. */
data class AttachmentPayload(
    val name: String,
    val mime: String,
    val text: String?,
    val imageB64: String?,
)

/**
 * Chat attachments. Text-like files are inlined into the user message (works
 * with every model); images become OpenAI-style image_url parts (multimodal
 * models only — when the endpoint rejects them [AgentClient] retries once
 * with [fallbackText]). Reading happens once at send time, capped.
 */
object Attachments {
    /** Max bytes read per image attachment. */
    const val MAX_IMAGE_BYTES = 6_000_000

    /** Max bytes read per text attachment. */
    const val MAX_TEXT_BYTES = 512_000

    /** Max chars of an inlined text attachment. */
    const val MAX_TEXT_CHARS = 120_000

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "json", "csv", "log", "xml", "html", "htm",
        "yaml", "yml", "ini", "cfg", "conf", "properties", "kt", "java", "py",
        "js", "ts", "tsx", "jsx", "c", "cpp", "h", "hpp", "cs", "go", "rs",
        "rb", "php", "sh", "bat", "ps1", "sql", "gradle", "kts", "toml", "tex",
    )

    fun isTextMime(mime: String, name: String): Boolean {
        val m = mime.lowercase()
        if (m.startsWith("text/")) return true
        if (m in setOf("application/json", "application/xml", "application/yaml", "application/javascript", "application/x-sh", "application/toml")) {
            return true
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXT
    }

    fun isImageMime(mime: String): Boolean = mime.lowercase().startsWith("image/")

    /** Decode picked bytes into a payload; null when empty/oversized/unknown. */
    fun payload(name: String, mime: String, bytes: ByteArray): AttachmentPayload? {
        if (bytes.isEmpty()) return null
        return when {
            isImageMime(mime) ->
                if (bytes.size > MAX_IMAGE_BYTES) {
                    null
                } else {
                    AttachmentPayload(name, mime.ifBlank { "image/png" }, null, Base64.getEncoder().encodeToString(bytes))
                }
            isTextMime(mime, name) -> {
                val full = String(bytes, 0, minOf(bytes.size, MAX_TEXT_BYTES), Charsets.UTF_8)
                val body = if (full.length > MAX_TEXT_CHARS) full.take(MAX_TEXT_CHARS) + "\n…（超长截断）" else full
                AttachmentPayload(name, mime.ifBlank { "text/plain" }, body, null)
            }
            else -> null
        }
    }

    /** Resolve a picked content uri (display name via ContentResolver). */
    fun nameOf(context: Context, uri: Uri): String = try {
        var name = "file"
        context.contentResolver
            .query(uri, null, null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                }
            }
        name
    } catch (t: Throwable) {
        "file"
    }

    /** Read a picked content uri fully; null when unreadable/unsupported. */
    fun load(context: Context, uri: Uri): AttachmentPayload? = try {
        val cr = context.contentResolver
        val name = nameOf(context, uri)
        val mime = cr.getType(uri) ?: ""
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        payload(name, mime, bytes)
    } catch (t: Throwable) {
        null
    }

    private fun textBlock(text: String, atts: List<AttachmentPayload>): String {
        var full = text
        atts.filter { it.text != null }.forEach { a ->
            full += "\n\n【附件 " + a.name + "】\n```\n" + a.text + "\n```"
        }
        return full
    }

    /** The user message content: a plain string, or a multimodal parts array. */
    fun buildContent(text: String, atts: List<AttachmentPayload>): Any {
        if (atts.isEmpty()) return text
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", textBlock(text, atts)))
        atts.filter { it.imageB64 != null }.forEach { a ->
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:" + a.mime + ";base64," + a.imageB64)),
            )
        }
        return parts
    }

    /** Text-only downgrade when the endpoint rejects multimodal parts. */
    fun fallbackText(text: String, atts: List<AttachmentPayload>): String {
        var full = textBlock(text, atts)
        val imgs = atts.filter { it.imageB64 != null }
        if (imgs.isNotEmpty()) {
            full += "\n\n（图片附件 " + imgs.joinToString("、") { it.name } + " 无法被当前模型读取，已忽略）"
        }
        return full
    }

    /** Short transcript suffix, e.g. "\n📎 a.txt、b.png". */
    fun summary(names: List<String>): String =
        if (names.isEmpty()) "" else "\n📎 " + names.joinToString("、")
}
