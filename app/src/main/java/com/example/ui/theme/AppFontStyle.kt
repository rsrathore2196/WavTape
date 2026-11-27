package com.example.ui.theme

import androidx.annotation.FontRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/**
 * Curated application typography options.
 *
 * Each typeface carries an [opticalScale] and [lineHeightScale] tuned to its glyph width,
 * x-height, and ascender/descender metrics so larger or wider display/serif/mono fonts
 * remain cleanly aligned and never overflow compact buttons, chips, headers, or lyrics rows.
 */
@Immutable
enum class AppFontStyle(
    val id: String,
    val displayName: String,
    val subtitle: String,
    @param:FontRes val fontRes: Int?,
    val opticalScale: Float = 1.0f,
    val lineHeightScale: Float = 1.0f
) {
    SYSTEM(
        id = "system",
        displayName = "System Font",
        subtitle = "Device default typeface",
        fontRes = null,
        opticalScale = 1.00f,
        lineHeightScale = 1.00f
    ),
    INTER(
        id = "inter",
        displayName = "Inter",
        subtitle = "Crisp studio sans",
        fontRes = R.font.inter,
        opticalScale = 0.96f,
        lineHeightScale = 1.02f
    ),
    PLUS_JAKARTA_SANS(
        id = "plus_jakarta_sans",
        displayName = "Plus Jakarta Sans",
        subtitle = "Modern geometric sans",
        fontRes = R.font.plus_jakarta_sans,
        opticalScale = 0.94f,
        lineHeightScale = 1.04f
    ),
    OUTFIT(
        id = "outfit",
        displayName = "Outfit",
        subtitle = "Sleek contemporary geometric",
        fontRes = R.font.outfit,
        opticalScale = 0.96f,
        lineHeightScale = 1.02f
    ),
    POPPINS(
        id = "poppins",
        displayName = "Poppins",
        subtitle = "Warm circular geometric",
        fontRes = R.font.poppins,
        opticalScale = 0.91f,
        lineHeightScale = 1.06f
    ),
    MONTSERRAT(
        id = "montserrat",
        displayName = "Montserrat",
        subtitle = "Bold urban display sans",
        fontRes = R.font.montserrat,
        opticalScale = 0.90f,
        lineHeightScale = 1.05f
    ),
    DM_SANS(
        id = "dm_sans",
        displayName = "DM Sans",
        subtitle = "Clean minimalist sans",
        fontRes = R.font.dm_sans,
        opticalScale = 0.96f,
        lineHeightScale = 1.03f
    ),
    MANROPE(
        id = "manrope",
        displayName = "Manrope",
        subtitle = "Balanced semi-geometric",
        fontRes = R.font.manrope,
        opticalScale = 0.94f,
        lineHeightScale = 1.04f
    ),
    URBANIST(
        id = "urbanist",
        displayName = "Urbanist",
        subtitle = "Airy neo-grotesque",
        fontRes = R.font.urbanist,
        opticalScale = 0.98f,
        lineHeightScale = 1.02f
    ),
    SORA(
        id = "sora",
        displayName = "Sora",
        subtitle = "Futuristic tech sans",
        fontRes = R.font.sora,
        opticalScale = 0.92f,
        lineHeightScale = 1.05f
    ),
    SPACE_GROTESK(
        id = "space_grotesk",
        displayName = "Space Grotesk",
        subtitle = "Studio neo-grotesk",
        fontRes = R.font.space_grotesk,
        opticalScale = 0.93f,
        lineHeightScale = 1.04f
    ),
    QUICKSAND(
        id = "quicksand",
        displayName = "Quicksand",
        subtitle = "Light rounded geometric",
        fontRes = R.font.quicksand,
        opticalScale = 0.94f,
        lineHeightScale = 1.04f
    ),
    NUNITO(
        id = "nunito",
        displayName = "Nunito",
        subtitle = "Smooth rounded sans",
        fontRes = R.font.nunito,
        opticalScale = 0.96f,
        lineHeightScale = 1.04f
    ),
    COMFORTAA(
        id = "comfortaa",
        displayName = "Comfortaa",
        subtitle = "Aesthetic wide rounded",
        fontRes = R.font.comfortaa,
        opticalScale = 0.88f,
        lineHeightScale = 1.06f
    ),
    FREDOKA(
        id = "fredoka",
        displayName = "Fredoka",
        subtitle = "Soft sculpted rounded",
        fontRes = R.font.fredoka,
        opticalScale = 0.95f,
        lineHeightScale = 1.03f
    ),
    JOSEFIN_SANS(
        id = "josefin_sans",
        displayName = "Josefin Sans",
        subtitle = "Elegant vintage geometric",
        fontRes = R.font.josefin_sans,
        opticalScale = 0.96f,
        lineHeightScale = 1.04f
    ),
    RALEWAY(
        id = "raleway",
        displayName = "Raleway",
        subtitle = "Refined slender sans",
        fontRes = R.font.raleway,
        opticalScale = 0.95f,
        lineHeightScale = 1.04f
    ),
    PLAYFAIR_DISPLAY(
        id = "playfair_display",
        displayName = "Playfair Display",
        subtitle = "Editorial luxury serif",
        fontRes = R.font.playfair_display,
        opticalScale = 0.95f,
        lineHeightScale = 1.08f
    ),
    LORA(
        id = "lora",
        displayName = "Lora",
        subtitle = "Contemporary literary serif",
        fontRes = R.font.lora,
        opticalScale = 0.95f,
        lineHeightScale = 1.06f
    ),
    CINZEL(
        id = "cinzel",
        displayName = "Cinzel",
        subtitle = "Classic inscribed capitals",
        fontRes = R.font.cinzel,
        opticalScale = 0.85f,
        lineHeightScale = 1.08f
    ),
    CORMORANT_GARAMOND(
        id = "cormorant_garamond",
        displayName = "Cormorant Garamond",
        subtitle = "Fine art display serif",
        fontRes = R.font.cormorant_garamond,
        opticalScale = 1.04f,
        lineHeightScale = 1.05f
    ),
    SYNE(
        id = "syne",
        displayName = "Syne",
        subtitle = "Avant-garde wide display",
        fontRes = R.font.syne,
        opticalScale = 0.85f,
        lineHeightScale = 1.06f
    ),
    JETBRAINS_MONO(
        id = "jetbrains_mono",
        displayName = "JetBrains Mono",
        subtitle = "Precision audio monospace",
        fontRes = R.font.jetbrains_mono,
        opticalScale = 0.87f,
        lineHeightScale = 1.06f
    );

    val fontFamily: FontFamily by lazy {
        val resId = fontRes ?: return@lazy FontFamily.Default
        FontFamily(Font(resId = resId, weight = FontWeight.Normal))
    }

    companion object {
        fun fromId(raw: String?): AppFontStyle {
            if (raw.isNullOrBlank()) return SYSTEM
            val normalized = raw.trim()
            return entries.firstOrNull {
                it.id.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            } ?: SYSTEM
        }
    }
}

val LocalAppFontStyle = staticCompositionLocalOf { AppFontStyle.SYSTEM }
