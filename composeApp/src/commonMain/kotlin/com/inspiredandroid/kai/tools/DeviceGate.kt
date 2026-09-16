package com.inspiredandroid.kai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/** One device-control tool execution awaiting the user's confirmation. */
data class DeviceActionRequest(
    val callId: String,
    val toolName: String,
    /** Human-readable summary of what the agent wants to do, e.g. `open QQ`. */
    val detail: String,
)

/**
 * Sensitive-device-action gate: device tools (app launching, screenshots,
 * touch/input injection, text entry) register here before running and the
 * loop suspends until the user decides. "Allow for this session" whitelists
 * every later device action until the process dies — the dialog is a consent
 * screen, not a per-call confirmation the user has to grind through.
 */
object DeviceActionGate {

    private val mutex = Mutex()
    private val decisions = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val _pending = MutableStateFlow<List<DeviceActionRequest>>(emptyList())

    /** Currently pending requests, newest last — rendered as confirmation dialogs. */
    val pending: StateFlow<List<DeviceActionRequest>> = _pending.asStateFlow()

    /** Set once the user picks "allow for this session"; reset only by process death. */
    @Volatile
    var sessionAllowed: Boolean = false
        private set

    suspend fun resetSessionAllowance() {
        mutex.withLock {
            sessionAllowed = false
        }
    }

    /** Registers the request and suspends until the user decides on it. */
    suspend fun awaitApproval(request: DeviceActionRequest): Boolean {
        if (sessionAllowed) return true
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

    /** Resolves a pending request from the UI; [forSession] whitelists the rest of it. */
    suspend fun decide(callId: String, approved: Boolean, forSession: Boolean = false) {
        mutex.withLock {
            if (approved && forSession) sessionAllowed = true
            decisions.remove(callId)?.complete(approved)
        }
    }
}

/**
 * Raised when a device tool finds Shizuku unauthorized: the chat UI shows a
 * dialog that deep-links the user into Shizuku's own permission flow
 * ([launchShizukuAuthorization]). A counter — every denial bumps it, and the
 * UI pops the dialog once per bump.
 */
object ShizukuGate {

    private val _needed = MutableStateFlow(0)

    /** Bump count; > 0 means at least one denial happened this process. */
    val needed: StateFlow<Int> = _needed.asStateFlow()

    fun request() {
        _needed.update { it + 1 }
    }
}

/**
 * Platform hook: sends the user into Shizuku's authorization UI. On Android
 * this requests the permission directly when the Shizuku server is up, or
 * opens the Shizuku manager app otherwise. No-op off Android.
 */
expect fun launchShizukuAuthorization()
