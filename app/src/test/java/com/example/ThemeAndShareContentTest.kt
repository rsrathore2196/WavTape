package com.example

import com.example.data.local.ThemePresets
import com.example.data.local.LyricsTranslationLanguages
import com.example.data.model.MusicTrack
import com.example.ui.util.buildTrackShareText
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.math.pow

class ThemeAndShareContentTest {
    private fun luminance(hex: String): Double {
        val rgb = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16) / 255.0 }
            .map { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722
    }
    @Test fun tenThemeIdentitiesHavePairedReadableDarkAndLightPalettes() {
        assertEquals(10, ThemePresets.allPresets.size)
        assertEquals(10, ThemePresets.allPresets.map { it.id }.distinct().size)
        assertEquals(10, ThemePresets.allPresets.map { it.name }.distinct().size)
        ThemePresets.allPresets.forEach { canonical ->
            assertSame(canonical, ThemePresets.findById(canonical.id))
            for (dark in listOf(false, true)) {
                val theme = ThemePresets.forMode(canonical, dark)
                assertEquals(canonical.id, theme.id)
                assertEquals(canonical.name, theme.name)
                assertEquals(dark, theme.isDark)
                val surfaces = listOf(theme.cardColorHex, theme.canvasColorHex) + theme.bgGradientColorsHex +
                    theme.cardGradientColorsHex + theme.bottomSheetGradientColorsHex
                for (text in listOf(theme.textPrimaryHex, theme.textSecondaryHex, theme.textMutedHex)) for (surface in surfaces) {
                    val a = luminance(text); val b = luminance(surface)
                    val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                    assertTrue("${theme.name}: $text on $surface has contrast $ratio", ratio >= 4.5)
                }
                assertEquals(theme.accentColorHex, theme.toCustomThemeState().accentColorHex)
            }
        }
        val amoled = ThemePresets.allPresets.single { it.isAmoled }
        assertEquals("#000000", amoled.canvasColorHex)
        assertEquals("#000000", amoled.cardColorHex)
        assertFalse(ThemePresets.forMode(amoled, false).isAmoled)
    }
    @Test fun shareTextKeepsTrackIdentityExactBulletCopyAndTheUnversionedLatestReleaseLink() {
        val text = buildTrackShareText(MusicTrack("id", "My Song", "My Artist", "", 0, "", ""))
        assertTrue(text.startsWith("Listening to \"My Song\" by My Artist on WavTape!"))
        assertEquals(listOf("• Hi Res Lossless Audio Support",
            "• Personalized Home and Recommendations according to your Mood",
            "• Beautiful Liquid Glass Interface with Custom themes",
            "• Synced Lyrics Support in your own language", "• Open Source, No Ads and Free to use"),
            text.lines().filter { it.startsWith("•") })
        assertTrue(text.endsWith("https://github.com/rsrathore2196/Xtreme-Player/releases/latest"))
    }
    @Test fun translationChoicesNormalizeTagsAndSearchEnglishAndNativeNames() {
        assertEquals("pa", LyricsTranslationLanguages.normalize("pa-IN"))
        assertEquals("zh-TW", LyricsTranslationLanguages.normalize("zh-Hant-HK"))
        assertEquals("zh-CN", LyricsTranslationLanguages.normalize("zh_CN"))
        assertEquals("", LyricsTranslationLanguages.normalize("unknown-language"))
        val options = LyricsTranslationLanguages.options(Locale.ENGLISH)
        assertEquals(options.size, options.map { it.tag }.distinct().size)
        assertTrue(options.first { it.tag == "pa" }.matches("Punjabi"))
        assertTrue(options.first { it.tag == "pa" }.matches("ਪੰਜਾਬੀ"))
        assertEquals("", options.first().tag)
    }
}
