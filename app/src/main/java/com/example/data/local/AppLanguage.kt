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
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** The interface and catalog locale are independent of the user's music preferences. */
internal object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "language_tag"
    // Regional/script variants use the same tags as the Android app locale configuration.
    internal val tags = (
        "af am ar as az bg bn brx ca cs da de doi el en es et fa fi fil fr gom gu he hi hr hu id it ja " +
            "ka kk km kn ko ks lo lt lv mai ml mni-Mtei mr ms my nb ne nl or pa pl pt ro ru sa sat sd " +
            "sk sl sr sv sw ta te th tr uk ur uz vi zh-CN zh-TW zu"
        ).split(' ').toSet()
    private val nativeNames = mapOf("hi" to "हिन्दी", "pa" to "ਪੰਜਾਬੀ", "brx" to "बर’",
        "gom" to "कोंकणी", "ks" to "کٲشُر", "mni-Mtei" to "ꯃꯤꯇꯩ ꯂꯣꯟ",
        "zh-CN" to "简体中文", "zh-TW" to "繁體中文")
    private val englishNames = mapOf("brx" to "Bodo", "gom" to "Konkani", "ks" to "Kashmiri",
        "mni-Mtei" to "Manipuri (Meitei)", "zh-CN" to "Chinese (Simplified)", "zh-TW" to "Chinese (Traditional)")
    private val combiningMarks = Regex("\\p{M}+")
    private val active = MutableStateFlow("en")
    val changes = active.asStateFlow()
    val catalogTag get() = active.value

    private fun legacyTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    internal data class Option(val tag: String, val nativeName: String, val name: String, val searchTerms: List<String>) {
        val hasInterfaceTranslation: Boolean get() = tag != "brx" && tag != "ks"
        fun matches(query: String): Boolean = searchTerms.any { query in it }
    }

    fun nativeName(tag: String): String = nativeNames[tag] ?: Locale.forLanguageTag(tag).let {
        it.getDisplayLanguage(it).replaceFirstChar { letter -> letter.titlecase(it) }
    }

    fun searchKey(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace(combiningMarks, "").lowercase(Locale.ROOT)

    fun options(displayLocale: Locale): List<Option> {
        val order = Collator.getInstance(displayLocale)
        return tags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val native = nativeName(tag)
            val english = englishNames[tag] ?: locale.getDisplayLanguage(Locale.ENGLISH)
            val localized = if (tag == "zh-CN" || tag == "zh-TW") locale.getDisplayName(displayLocale)
                else locale.getDisplayLanguage(displayLocale)
            val name = localized.takeUnless { it.equals(locale.language, true) } ?: english
            val terms = listOf(tag, native, name, english).map(::searchKey).distinct()
            Option(tag, native, name.replaceFirstChar { it.titlecase(displayLocale) }, terms)
        }.sortedWith { first, second -> order.compare(first.name, second.name) }
    }

    private fun supportedTag(locale: Locale): String? {
        val tag = when (locale.language) {
            "zh" -> when (locale.script) {
                "Hans" -> "zh-CN"
                "Hant" -> "zh-TW"
                else -> if (locale.country in setOf("HK", "MO", "TW")) "zh-TW" else "zh-CN"
            }
            "iw" -> "he"
            "in" -> "id"
            "mni" -> "mni-Mtei"
            "kok" -> "gom"
            "no" -> "nb"
            "tl" -> "fil"
            else -> locale.language
        }
        return tag.takeIf(tags::contains)
    }

    fun selection(context: Context): String {
        val locale = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)
        } else Locale.forLanguageTag(legacyTag(context))
        return locale?.let(::supportedTag).orEmpty()
    }

    fun refresh(context: Context) {
        val system = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.let { manager ->
                val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (preferences.contains(KEY)) {
                    val tag = supportedTag(Locale.forLanguageTag(preferences.getString(KEY, "").orEmpty()))
                    if (tag != null && manager.applicationLocales.isEmpty) {
                        manager.applicationLocales = LocaleList.forLanguageTags(tag)
                    }
                    // Android owns the choice after migration, including a later reset to system default.
                    preferences.edit().remove(KEY).apply()
                }
                manager.systemLocales
            }
        } else null
        val locales = system ?: Resources.getSystem().configuration.locales
        active.value = resolve(selection(context), locales)
    }

    internal fun resolve(selection: String, system: LocaleList): String =
        supportedTag(Locale.forLanguageTag(selection))
            ?: (0 until system.size()).firstNotNullOfOrNull { system[it]?.let(::supportedTag) }
            ?: "en"

    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = supportedTag(Locale.forLanguageTag(legacyTag(context))) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        return context.createConfigurationContext(configuration)
    }

    fun set(activity: Activity, tag: String) {
        if (tag.isNotEmpty() && tag !in tags || selection(activity) == tag) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
        refresh(activity)
    }
}
