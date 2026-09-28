package com.billtt.riddle

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** Explicit app preference, with English fallback on devices using other languages. */
object AppLanguage {
    val choices = listOf("system", "en", "vi")
    fun selected(context: Context): String = context.getSharedPreferences("riddle", Context.MODE_PRIVATE)
        .getString("app_language", "system").orEmpty().takeIf { it in choices } ?: "system"
    private fun configuration(context: Context): Configuration {
        val selected = selected(context)
        val language = if (selected == "system") {
            if (Resources.getSystem().configuration.locales[0].language == "vi") "vi" else "en"
        } else selected
        return Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    fun wrap(context: Context): Context = context.createConfigurationContext(configuration(context))
    @Suppress("DEPRECATION")
    fun apply(context: Context, language: String) {
        require(language in choices)
        context.getSharedPreferences("riddle", Context.MODE_PRIVATE).edit().putString("app_language", language).apply()
        // Refresh in place so a language change does not destroy handwriting on the page.
        context.resources.updateConfiguration(configuration(context), context.resources.displayMetrics)
    }
}
