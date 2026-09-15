package com.inspiredandroid.kai.tools

/**
 * One agent-registered extension for display in Settings → Tools; the
 * per-platform actuals back it with the platform registry (Android:
 * ExtensionStore, elsewhere: none).
 */
data class AgentExtension(
    val id: String,
    val name: String,
    val desc: String,
    val cmd: String,
    val enabled: Boolean,
)

expect fun getAgentExtensions(): List<AgentExtension>

expect fun setAgentExtensionEnabled(id: String, enabled: Boolean)

expect fun removeAgentExtension(id: String)
