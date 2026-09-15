package com.inspiredandroid.kai.tools

actual fun getAgentExtensions(): List<AgentExtension> = emptyList()

actual fun setAgentExtensionEnabled(id: String, enabled: Boolean) {}

actual fun removeAgentExtension(id: String) {}
