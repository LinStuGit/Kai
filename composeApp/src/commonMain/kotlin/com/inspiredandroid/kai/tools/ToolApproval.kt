package com.inspiredandroid.kai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One tool execution awaiting the user's decision in manual approval mode. */
data class ToolApprovalRequest(
    val callId: String,
    val toolName: String,
    val displayName: String,
    val argsJson: String,
)

/**
 * Gate between the agent's tool loop and the user: in manual approval mode
 * every tool call is registered here and the loop suspends until the user
 * approves or rejects it via the chat UI ([decide]). Cancelling the run also
 * cancels the awaiting coroutines, which removes the pending entries in their
 * finally blocks.
 */
object ToolApprovalController {

    private val mutex = Mutex()
    private val decisions = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val _pending = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())

    /** Currently pending requests, newest last — rendered as approval cards. */
    val pending: StateFlow<List<ToolApprovalRequest>> = _pending.asStateFlow()

    /** Registers the request and suspends until the user decides on it. */
    suspend fun awaitApproval(request: ToolApprovalRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        mutex.withLock {
            decisions[request.callId] = deferred
            _pending.update { it + request }
        }
        try {
            return deferred.await()
        } finally {
            mutex.withLock {
                decisions.remove(request.callId)
                _pending.update { list -> list.filterNot { it.callId == request.callId } }
            }
        }
    }

    /** Resolves a pending request from the UI; no-op if it's already gone. */
    suspend fun decide(callId: String, approved: Boolean) {
        mutex.withLock {
            decisions.remove(callId)?.complete(approved)
        }
    }
}
