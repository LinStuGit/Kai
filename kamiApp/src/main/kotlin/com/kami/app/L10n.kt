package com.kami.app

import androidx.compose.runtime.mutableStateOf

/**
 * Lightweight in-app i18n: UI strings are written as [s]("中文", "English")
 * call sites. The choice lives in snapshot state (composables recompose on
 * switch) backed by prefs. zh is the default; Settings offers English.
 */
object L10n {
    const val PREFS = "kami_l10n"
    const val ZH = "zh"
    const val EN = "en"

    /** Snapshot state: reading it in a composable makes the UI follow the switch. */
    val lang = mutableStateOf(ZH)

    fun init(context: android.content.Context) {
        lang.value = context
            .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString("lang", ZH)
            ?.takeIf { it == EN } ?: ZH
    }

    fun set(context: android.content.Context, l: String) {
        lang.value = if (l == EN) EN else ZH
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("lang", lang.value)
            .apply()
    }

    val isEn: Boolean get() = lang.value == EN

    /** Bilingual literal, keeps the call site readable: s("中文", "English"). */
    fun s(zh: String, en: String): String = if (isEn) en else zh
}
