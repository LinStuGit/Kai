package com.inspiredandroid.kai.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between the agent tool loop (repository) and the Android overlay
 * service: run liveness, whether the main activity is in front (the overlay
 * hides itself while it is), and the cancel hook the chat view model installs.
 */
object AgentOverlayController {

    data class RunState(
        val active: Boolean = false,
        /** Bumped on every run start so observers can reset per-run UI state. */
        val seq: Long = 0,
    )

    private val _runState = MutableStateFlow(RunState())
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    /** True while the main activity is visible — the overlay stays hidden then. */
    val activityVisible = MutableStateFlow(false)

    /** Installed by the chat view model; cancels the in-flight run like the in-app stop button. */
    var onCancel: (() -> Unit)? = null

    fun setRunActive(active: Boolean) {
        _runState.value = if (active) {
            RunState(active = true, seq = _runState.value.seq + 1)
        } else {
            _runState.value.copy(active = false)
        }
    }
}

/** Platform hook: the tool loop started/stopped executing tools (Android starts/stops the overlay service). */
expect fun notifyAgentRunActive(active: Boolean)

expect fun isOverlayPermissionGranted(): Boolean

/** Opens the system page where the user can grant the draw-over-other-apps permission. */
expect fun requestOverlayPermission()
