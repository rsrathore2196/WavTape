package com.example

import android.app.Application
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
        assertEquals("pa", AppLanguage.resolve("", device))
        assertEquals("hi", AppLanguage.resolve("hi", device))
        assertEquals("en", AppLanguage.resolve("", LocaleList.forLanguageTags("es-ES")))
        assertEquals("", AppLanguage.selection(app))
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
        assertEquals(punjabi, CatalogDisplayText.inLanguage(punjabi, "pa"))
        assertEquals("Live Mix (Official Audio)", CatalogDisplayText.inLanguage("Live Mix (Official Audio)", "pa"))
    }
}
