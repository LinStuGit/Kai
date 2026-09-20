package com.kami.app

import android.content.Context

/**
 * Resolved endpoint facts for one chat request. `key` is only meaningful
 * for custom endpoints — madmodel mode gets a pooled JWT instead.
 */
data class KamiEndpoint(val base: String, val model: String, val key: String)

/**
 * Model wiring store: the fixed madmodel gateway stays the default, but
 * which gateway model to call is selectable, and any other OpenAI
 * compatible endpoint can be wired in (base URL + key + model).
 */
object ModelStore {
    const val PREFS = "kami_model"
    const val MODE_MADMODEL = "madmodel"
    const val MODE_CUSTOM = "custom"

    /** Models offered by the madmodel gateway (agent-facing defaults). */
    val MADMODEL_MODELS = listOf(
        "DeepSeek-V4-Flash-0731",
        "DeepSeek-R1-Distill-Qwen-32B",
        "DeepSeek-R1-W8A8",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): String =
        prefs(context).getString("mode", MODE_MADMODEL)?.takeIf { it == MODE_CUSTOM } ?: MODE_MADMODEL

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString("mode", if (mode == MODE_CUSTOM) MODE_CUSTOM else MODE_MADMODEL).apply()
    }

    fun madmodelModel(context: Context): String =
        prefs(context).getString("madmodel_model", null)
            ?.takeIf { it in MADMODEL_MODELS } ?: MadModel.MODEL

    fun setMadmodelModel(context: Context, model: String) {
        prefs(context).edit().putString("madmodel_model", model).apply()
    }

    fun customBase(context: Context): String = prefs(context).getString("custom_base", "").orEmpty()
    fun customKey(context: Context): String = prefs(context).getString("custom_key", "").orEmpty()
    fun customModel(context: Context): String = prefs(context).getString("custom_model", "").orEmpty()

    fun setCustom(context: Context, base: String, key: String, model: String) {
        prefs(context).edit()
            .putString("custom_base", base.trim())
            .putString("custom_key", key.trim())
            .putString("custom_model", model.trim())
            .apply()
    }

    /** The endpoint the agent will actually talk to right now. */
    fun current(context: Context): KamiEndpoint = if (mode(context) == MODE_CUSTOM) {
        KamiEndpoint(customBase(context), customModel(context), customKey(context))
    } else {
        KamiEndpoint(MadModel.BASE, madmodelModel(context), "")
    }

    /** True when the current config can serve a request (key/model non-blank). */
    fun ready(context: Context): Boolean {
        val e = current(context)
        return e.base.startsWith("http") && e.model.isNotBlank() &&
            (mode(context) == MODE_MADMODEL || e.key.isNotBlank())
    }

    /** One-line summary for the settings card. */
    fun describe(context: Context): String {
        val e = current(context)
        return if (mode(context) == MODE_CUSTOM) {
            "自定义端点  ${e.base}\n模型  ${e.model}"
        } else {
            "madmodel 网关  ${e.base}\n模型  ${e.model}"
        }
    }
}
