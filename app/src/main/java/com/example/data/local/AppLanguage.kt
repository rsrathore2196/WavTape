package com.example.data.local

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** The interface and catalog locale are independent of the user's music preferences. */
internal object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "language_tag"
    val choices = linkedMapOf("en" to "English", "hi" to "हिन्दी", "pa" to "ਪੰਜਾਬੀ")
    private val active = MutableStateFlow("en")
    val changes = active.asStateFlow()
    val catalogTag get() = active.value

    private fun legacyTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun selection(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.language.orEmpty()
        } else legacyTag(context)
        return tag.takeIf(choices::containsKey).orEmpty()
    }

    fun refresh(context: Context) {
        val system = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null
        val locales = system ?: Resources.getSystem().configuration.locales
        active.value = resolve(selection(context), locales)
    }

    internal fun resolve(selection: String, system: LocaleList): String =
        selection.takeIf(choices::containsKey)
            ?: (0 until system.size()).firstNotNullOfOrNull { system[it]?.language?.takeIf(choices::containsKey) }
            ?: "en"

    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = legacyTag(context).takeIf(choices::containsKey) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        return context.createConfigurationContext(configuration)
    }

    fun set(activity: Activity, tag: String) {
        if (tag.isNotEmpty() && tag !in choices || selection(activity) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
        refresh(activity)
    }
}
