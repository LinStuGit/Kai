package com.inspiredandroid.kai.network.dtos.openaicompatible

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
         * Whichever payload shape the provider used: the regular `message`, or a
         * `delta` frame assembled into one. Null when neither carries content.
         */
        val effectiveMessage: Message?
            get() = message ?: delta?.toMessage()
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
                    Message.ToolCall(
                        id = first.id ?: "call-delta-${first.index}",
                        type = first.type,
                        function = Message.FunctionCall(
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
        /** Whichever reasoning field the provider used, normalized to one accessor. */
        val effectiveReasoning: String?
            get() = reasoningContent ?: reasoning

        /** Returns [content] if non-blank, otherwise falls back to reasoning. */
        val effectiveContent: String?
            get() {
                val raw = content?.takeIf { it.isNotBlank() } ?: effectiveReasoning
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
            get() = content.isNullOrBlank() && !effectiveReasoning.isNullOrBlank()

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
