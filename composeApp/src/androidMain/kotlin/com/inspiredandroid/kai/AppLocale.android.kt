package com.inspiredandroid.kai

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.inspiredandroid.kai.data.AppSettings
import org.koin.java.KoinJavaComponent.inject
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Tracks the foreground activity so [applyAppLocale] can recreate it after
 * switching language on API < 33 (where there is no system per-app locale).
 */
object AppLocaleHolder {
    @Volatile
    var activity: WeakReference<Activity>? = null
}

actual fun applyAppLocale(tag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // System-managed per-app locale: persists across restarts and recreates
        // all activities by itself.
        val context: Context by inject(Context::class.java)
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList(Locale.forLanguageTag(tag.toBcp47()))
            }
    } else {
        Locale.setDefault(if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag.toBcp47()))
        AppLocaleHolder.activity?.get()?.recreate()
    }
}

/** Resource-qualifier spelling (zh-rTW) → BCP-47 (zh-TW). */
private fun String.toBcp47(): String = replace("-r", "-")

/** Pre-33 locale wrapping for [android.content.Context]; no-op on empty tag / 33+. */
fun wrapContextLocale(base: Context, tag: String): Context {
    if (tag.isEmpty() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val locale = Locale.forLanguageTag(tag.toBcp47())
    Locale.setDefault(locale)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
}

fun currentLanguageTag(): String = try {
    val appSettings: AppSettings by inject(AppSettings::class.java)
    appSettings.getLanguage()
} catch (_: Throwable) {
    ""
}
