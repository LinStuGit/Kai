package com.inspiredandroid.kai.network.dtos.openaicompatible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val toolCallMarkerRegex = Regex("<TOOLCALL>[\\s\\S]*?</TOOLCALL>|<TOOLCALL>[\\s\\S]*$")

private val thinkBlockRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

private val chunkFrameJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * A streaming chunk frame some relays leak verbatim into `message.content` as
 * plain text (the structured `tool_calls` field left null) — e.g. paratera's
 * GLM-Z1-Flash channel. Parsing it back out recovers the real tool call.
 */
@Serializable
private data class ChunkFrame(
    @SerialName("finish_reason")
    val finishReason: String? = null,
    val delta: OpenAICompatibleChatResponseDto.Delta? = null,
)

/**
 * Reads `message.content` whether the provider sends a plain string or an OpenAI-style array of
 * content blocks (e.g. `[{"type":"text","text":"..."}]`). Array forms are flattened by
 * concatenating the `text` fields, so downstream code keeps seeing a simple [String]. Applied only
 * to nullable fields, so kotlinx handles a literal JSON `null` before this runs.
 */
internal object FlexibleContentSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleContent", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.content

            is JsonArray -> element.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("")

            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@Serializable
data class OpenAICompatibleChatResponseDto(
    val choices: List<Choice>,
) {
    @Serializable
    data class Choice(
        val message: Message? = null,
        // Some OpenAI-compatible relays answer non-streaming requests with a
        // chat.completion.chunk body anyway (delta instead of message).
        val delta: Delta? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    ) {
        /**
         * Whichever payload shape the provider used: the regular `message`, a
         * `delta` frame assembled into one, or — when content is a leaked chunk
         * JSON carrying the real tool calls — that recovered message in place of
         * the garbage text. Null when nothing usable is present.
         */
        val effectiveMessage: Message?
            get() = (message ?: delta?.toMessage())?.let { payload ->
                val recovered = payload.embeddedChunkMessage ?: return@let payload
                recovered.copy(
                    reasoningContent = recovered.reasoningContent ?: payload.reasoningContent,
                    reasoning = recovered.reasoning ?: payload.reasoning,
                )
            }
    }

    @Serializable
    data class Delta(
        val role: String? = null,
        @Serializable(with = FlexibleContentSerializer::class)
        val content: String? = null,
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
        val reasoning: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCallDelta>? = null,
    ) {
        /**
         * Folds the delta into a regular [Message]: tool-call fragments are merged
         * by their stream index (id/name come on the first fragment, argument text
         * may be split across fragments sharing it). Returns null when the delta
         * carries nothing usable (e.g. a role-only first chunk).
         */
        fun toMessage(): Message? {
            val mergedCalls = toolCalls.orEmpty()
                .groupBy { it.index }
                .map { (_, fragments) ->
                    val first = fragments.first()
                    val function = first.function ?: return@map null
                    ToolCall(
                        id = first.id ?: "call-delta-${first.index}",
                        type = first.type,
                        function = FunctionCall(
                            name = function.name.orEmpty(),
                            arguments = fragments.joinToString("") { it.function?.arguments.orEmpty() },
                        ),
                    )
                }
                .filterNotNull()
            val hasContent = !content.isNullOrBlank() || !reasoningContent.isNullOrBlank() || !reasoning.isNullOrBlank()
            if (!hasContent && mergedCalls.isEmpty()) return null
            return Message(
                role = role,
                content = content,
                reasoningContent = reasoningContent,
                reasoning = reasoning,
                toolCalls = mergedCalls.takeIf { it.isNotEmpty() },
            )
        }
    }

    @Serializable
    data class ToolCallDelta(
        val id: String? = null,
        val type: String = "function",
        val function: FunctionCallDelta? = null,
        // Present on chunk frames; irrelevant once merged by index.
        val index: Int = 0,
    )

    @Serializable
    data class FunctionCallDelta(
        val name: String? = null,
        val arguments: String? = null,
    )

    @Serializable
    data class Message(
        val role: String? = null,
        @Serializable(with = FlexibleContentSerializer::class)
        val content: String? = null,
        // DeepSeek returns `reasoning_content`; OpenRouter returns `reasoning`.
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
        val reasoning: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall>? = null,
    ) {
        /** Reasoning emitted as a `<think>…</think>` block inside content (GLM-Z1 style): extracted text to original remainder. */
        private val thinkSplit: Pair<String?, String?> by lazy {
            val raw = content ?: return@lazy null to null
            if (!raw.contains("<think>")) return@lazy null to raw
            val match = thinkBlockRegex.find(raw)
            if (match != null) {
                val extracted = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
                val rest = raw.removeRange(match.range).trim().takeIf { it.isNotEmpty() }
                extracted to rest
            } else {
                // Unterminated <think> (generation cut off): the tail is all reasoning.
                val tail = raw.substringAfter("<think>").trim().takeIf { it.isNotEmpty() }
                tail to null
            }
        }

        /** Content with any `<think>` block removed; null when nothing visible remains. */
        private val visibleContent: String?
            get() {
                val raw = content ?: return null
                return if (raw.contains("<think>")) thinkSplit.second else raw.takeIf { it.isNotBlank() }
            }

        /**
         * The real payload when `content` is a leaked streaming-chunk JSON: the
         * delta's tool calls assembled into a Message. Only fires when the chunk
         * actually carries tool calls, so answers that legitimately quote chunk
         * JSON are never rewritten. Null otherwise.
         */
        val embeddedChunkMessage: Message? by lazy {
            val raw = visibleContent ?: return@lazy null
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{") || !trimmed.contains("\"delta\"")) return@lazy null
            val frame = runCatching { chunkFrameJson.decodeFromString(ChunkFrame.serializer(), trimmed) }
                .getOrNull() ?: return@lazy null
            val assembled = frame.delta?.toMessage() ?: return@lazy null
            assembled.takeIf { !it.toolCalls.isNullOrEmpty() }
        }

        /** Whichever reasoning field the provider used, plus inline `<think>` text, normalized to one accessor. */
        val effectiveReasoning: String?
            get() = reasoningContent ?: reasoning ?: thinkSplit.first

        /** Returns [content] (minus `<think>`) if non-blank, otherwise falls back to reasoning. */
        val effectiveContent: String?
            get() {
                val raw = visibleContent ?: effectiveReasoning
                // Some providers (e.g. Ollama) embed tool calls as <TOOLCALL>[...] markers
                // in the content field alongside structured tool_calls — strip them.
                if (raw != null && !toolCalls.isNullOrEmpty()) {
                    val stripped = raw.replace(toolCallMarkerRegex, "").trim()
                    return stripped.takeIf { it.isNotBlank() }
                }
                return raw
            }

        /** True when the effective content comes from reasoning rather than [content]. */
        val isContentFromReasoning: Boolean
            get() = visibleContent.isNullOrBlank() && !effectiveReasoning.isNullOrBlank()

        /**
         * Reasoning trace with the answer text trimmed off if the provider appended it.
         * LongCat (flash thinking) and a few others stream the final answer as the tail of
         * `reasoning_content`, then return the same text in `content` — without this, the
         * "Thinking" section duplicates the answer rendered below it.
         */
        fun reasoningTraceFor(answer: String?): String? {
            val reasoning = effectiveReasoning ?: return null
            if (answer.isNullOrBlank() || isContentFromReasoning) return reasoning
            val trimmedReasoning = reasoning.trimEnd()
            val trimmedAnswer = answer.trim()
            if (!trimmedReasoning.endsWith(trimmedAnswer)) return reasoning
            return trimmedReasoning.removeSuffix(trimmedAnswer).trimEnd().takeIf { it.isNotBlank() }
        }
    }

    @Serializable
    data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: FunctionCall,
    )

    @Serializable
    data class FunctionCall(
        val name: String,
        val arguments: String,
    )
}
