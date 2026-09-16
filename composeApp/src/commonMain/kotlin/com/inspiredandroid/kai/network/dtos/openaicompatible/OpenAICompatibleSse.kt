package com.inspiredandroid.kai.network.dtos.openaicompatible

import kotlinx.serialization.json.Json

/** Lenient parser for individual `data:` frames — same tolerance as the client's ContentNegotiation. */
private val sseFrameJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Merges an OpenAI-compatible `data: {...}` SSE stream into a single response.
 * Some relays answer non-streaming requests with a streamed body anyway (GLM /
 * Zhipu channels, new-api tool-call simulation, …). Content and reasoning texts
 * are concatenated; tool-call fragments are merged by stream index (id/name on
 * the first fragment, argument text concatenated across the rest).
 */
internal fun assembleSseChunks(body: String): OpenAICompatibleChatResponseDto {
    var role: String? = null
    var content: String? = null
    var reasoningContent: String? = null
    var reasoning: String? = null
    var finishReason: String? = null
    val callIds = mutableMapOf<Int, String>()
    val callNames = mutableMapOf<Int, String>()
    val callArgs = mutableMapOf<Int, StringBuilder>()

    for (line in body.lineSequence()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) continue
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") continue
        val frame = runCatching {
            sseFrameJson.decodeFromString(OpenAICompatibleChatResponseDto.serializer(), payload)
        }.getOrNull() ?: continue
        val choice = frame.choices.firstOrNull() ?: continue
        val delta = choice.delta
        val message = choice.message

        if (role == null) role = delta?.role ?: message?.role
        (delta?.content ?: message?.content)?.let { content = (content ?: "") + it }
        (delta?.reasoningContent ?: message?.reasoningContent)?.let { reasoningContent = (reasoningContent ?: "") + it }
        (delta?.reasoning ?: message?.reasoning)?.let { reasoning = (reasoning ?: "") + it }
        choice.finishReason?.let { finishReason = it }

        for (fragment in delta?.toolCalls.orEmpty()) {
            fragment.id?.let { callIds[fragment.index] = it }
            fragment.function?.name?.takeIf { it.isNotEmpty() }?.let { callNames[fragment.index] = it }
            callArgs.getOrPut(fragment.index) { StringBuilder() }.append(fragment.function?.arguments ?: "")
        }
        for (call in message?.toolCalls.orEmpty()) {
            callIds[0] = call.id
            callNames[0] = call.function.name
            callArgs.getOrPut(0) { StringBuilder() }.append(call.function.arguments)
        }
    }

    val mergedCalls = callArgs.keys.sorted().mapNotNull { index ->
        val name = callNames[index] ?: return@mapNotNull null
        OpenAICompatibleChatResponseDto.ToolCall(
            id = callIds[index] ?: "call-delta-$index",
            function = OpenAICompatibleChatResponseDto.FunctionCall(
                name = name,
                arguments = callArgs[index]?.toString().orEmpty().ifEmpty { "{}" },
            ),
        )
    }
    return OpenAICompatibleChatResponseDto(
        choices = listOf(
            OpenAICompatibleChatResponseDto.Choice(
                message = OpenAICompatibleChatResponseDto.Message(
                    role = role,
                    content = content,
                    reasoningContent = reasoningContent,
                    reasoning = reasoning,
                    toolCalls = mergedCalls.takeIf { it.isNotEmpty() },
                ),
                finishReason = finishReason,
            ),
        ),
    )
}

/** Tolerant JSON config for reading the chat body outside ContentNegotiation. */
internal val chatPayloadJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
    explicitNulls = false
}
