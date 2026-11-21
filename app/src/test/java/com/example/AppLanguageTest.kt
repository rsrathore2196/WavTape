package com.example

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.example.data.cache.HomeCacheIdentity
import com.example.data.local.AppLanguage
import com.example.data.local.CatalogDisplayText
import com.example.data.local.UserProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class AppLanguageTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun systemDefaultFollowsSupportedDeviceLanguagesAndExplicitChoiceWins() {
        val device = LocaleList.forLanguageTags("es-ES,pa-IN,en-GB")
        assertEquals("es", AppLanguage.resolve("", device))
        assertEquals("hi", AppLanguage.resolve("hi", device))
        assertEquals("en", AppLanguage.resolve("", LocaleList.forLanguageTags("tlh")))
        assertEquals("", AppLanguage.selection(app))
    }

    @Test fun configurationIncludesTheSameLanguagesAsThePicker() {
        val tags = app.resources.getXml(R.xml.app_locales).use { parser ->
            buildSet {
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                        add(parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name"))
                    }
                }
            }
        }
        assertEquals(AppLanguage.tags, tags)
        assertTrue(tags.containsAll("as bn brx doi gom gu hi kn ks mai ml mni-Mtei mr ne or pa sa sat sd ta te ur".split(' ')))
        assertEquals("zh-TW", AppLanguage.resolve("", LocaleList.forLanguageTags("zh-Hant-HK")))
        assertEquals("zh-CN", AppLanguage.resolve("", LocaleList.forLanguageTags("zh-Hans-TW")))
        assertEquals("mni-Mtei", AppLanguage.resolve("mni", LocaleList.getEmptyLocaleList()))
    }

    @Test fun labelsAreSearchableInEnglishAndNativeScriptAndTranslatedResourcesFollowSelection() {
        val options = AppLanguage.options(Locale.ENGLISH)
        val spanish = options.single { it.tag == "es" }
        assertTrue(spanish.matches(AppLanguage.searchKey("espanol")))
        assertTrue(spanish.matches(AppLanguage.searchKey("SPANISH")))
        val tamil = options.single { it.tag == "ta" }
        assertTrue(tamil.matches(AppLanguage.searchKey("தமிழ்")))
        assertFalse(options.single { it.tag == "ks" }.hasInterfaceTranslation)
        fun resource(tag: String, id: Int): String = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        ).getString(id)
        assertEquals("முகப்பு", resource("ta-IN", R.string.nav_home))
        assertEquals("Idioma de la aplicación", resource("es", R.string.app_language))
        assertEquals("音樂庫", resource("zh-TW", R.string.nav_library))
        assertEquals("音乐库", resource("zh-CN", R.string.nav_library))
    }

    @Test fun languageResourcesApplyToDifferentAppDestinations() {
        val hindi = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("hi")))
        })
        val punjabi = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag("pa")))
        })
        assertEquals("ऐप की भाषा", hindi.getString(R.string.app_language))
        assertEquals("ऑनलाइन संगीत खोजें", hindi.getString(R.string.search_title))
        assertEquals("ਤੁਹਾਡੀ ਲਾਇਬ੍ਰੇਰੀ", punjabi.getString(R.string.library_title))
        assertEquals("ਅਨੰਤ ਰੇਡੀਓ", punjabi.getString(R.string.infinite_radio))
    }

    @Test @Config(sdk = [32])
    fun savedLanguageSeparatesHomeCachesAndWrapsThePreAndroid13ActivityContext() {
        val prefs = app.getSharedPreferences("app_language", Context.MODE_PRIVATE)
        val profile = UserProfile(countryCode = "IN", languages = listOf("Hindi", "Punjabi"))
        val originalHome = HomeCacheIdentity.home(profile, "anonymous")
        try {
            prefs.edit().putString("language_tag", "pa").commit()
            AppLanguage.refresh(app)
            assertEquals("pa", AppLanguage.localizedContext(app).resources.configuration.locales[0].language)
            assertEquals("IN@pa|anonymous", HomeCacheIdentity.home(profile, "anonymous"))
            assertNotEquals(originalHome, HomeCacheIdentity.home(profile, "anonymous"))
            assertTrue(HomeCacheIdentity.profile(profile, "anonymous").endsWith("|hindi,punjabi"))
        } finally {
            prefs.edit().clear().commit()
            AppLanguage.refresh(app)
        }
    }

    @Test fun catalogPresentationRomanizesOnlyMismatchedScriptsAndKeepsTrackModifiers() {
        val hindi = "दिल से (Slowed + Reverb)"
        val punjabi = "ਗੀਤ ੧"
        assertEquals("Dil Se (Slowed + Reverb)", CatalogDisplayText.inLanguage(hindi, "en"))
        assertEquals("Geet 1", CatalogDisplayText.inLanguage(punjabi, "en"))
        assertEquals(hindi, CatalogDisplayText.inLanguage(hindi, "hi"))
        assertEquals(hindi, CatalogDisplayText.inLanguage(hindi, "mr"))
        assertEquals(hindi, CatalogDisplayText.inLanguage(hindi, "ne"))
        assertEquals(punjabi, CatalogDisplayText.inLanguage(punjabi, "pa"))
        assertEquals("Live Mix (Official Audio)", CatalogDisplayText.inLanguage("Live Mix (Official Audio)", "pa"))
    }

    @Test @Config(sdk = [34])
    fun androidUpgradeTransfersTheExistingLanguageChoiceOnlyOnce() {
        val prefs = app.getSharedPreferences("app_language", Context.MODE_PRIVATE)
        val localeManager = app.getSystemService(LocaleManager::class.java)
        try {
            prefs.edit().putString("language_tag", "pa").commit()
            AppLanguage.refresh(app)
            assertEquals("pa", AppLanguage.selection(app))
            assertEquals("pa", AppLanguage.catalogTag)
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            AppLanguage.refresh(app)
            assertEquals("", AppLanguage.selection(app))

            localeManager.applicationLocales = LocaleList.forLanguageTags("ta")
            prefs.edit().putString("language_tag", "pa").commit()
            AppLanguage.refresh(app)
            assertEquals("ta", AppLanguage.selection(app))
            assertFalse(prefs.contains("language_tag"))
        } finally {
            localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            prefs.edit().remove("language_tag").commit()
            AppLanguage.refresh(app)
        }
    }
}
