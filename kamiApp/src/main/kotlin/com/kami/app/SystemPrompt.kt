package com.kami.app

import android.content.Context

/**
 * User-editable system prompt (Settings → 系统提示词). Whatever the user
 * saves replaces the built-in prompt in every agent turn; empty or reset
 * falls back to [AgentClient.SYSTEM_PROMPT]. Persisted memories are still
 * appended after it by [AgentClient.systemContent].
 */
object SystemPromptStore {

    private const val PREFS = "kami_prompt"
    private const val KEY = "system_prompt"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): String =
        if (!::appContext.isInitialized) {
            AgentClient.SYSTEM_PROMPT
        } else {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)?.trim().orEmpty()
                .ifEmpty { AgentClient.SYSTEM_PROMPT }
        }

    /** True when the user has a custom prompt in effect. */
    fun isCustom(): Boolean = ::appContext.isInitialized &&
        !appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null).isNullOrBlank()

    /** Save a custom prompt; blank or the verbatim default clears it. */
    fun set(value: String) {
        if (!::appContext.isInitialized) return
        val v = value.trim()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (v.isEmpty() || v == AgentClient.SYSTEM_PROMPT) remove(KEY) else putString(KEY, v)
            }
            .apply()
    }
}