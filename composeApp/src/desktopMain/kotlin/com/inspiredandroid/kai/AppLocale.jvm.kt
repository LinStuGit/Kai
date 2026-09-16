package com.inspiredandroid.kai

import java.util.Locale

actual fun applyAppLocale(tag: String) {
    Locale.setDefault(if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag))
}
