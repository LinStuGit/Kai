package com.inspiredandroid.kai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bumped whenever the app-language preference changes. Platforms that can't
 * recreate the whole UI (desktop, web) key the content tree on this so every
 * [org.jetbrains.compose.resources.stringResource] re-reads the new locale.
 */
var localeVersion by mutableStateOf(0)
    private set

fun bumpLocaleVersion() {
    localeVersion++
}

/**
 * Applies the app-language preference to the platform. An empty tag means
 * "follow the system locale". No-op on platforms that can't override the
 * system locale (iOS, wasm).
 */
expect fun applyAppLocale(tag: String)
