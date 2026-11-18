package com.example.data.local

import androidx.compose.runtime.Immutable

/** Fixed tonal roles keep preset rendering deterministic across Android versions. */
@Immutable
internal data class ThemeTonePalette(
    val family: String, val dark: Boolean,
    val primary: String,
    val onPrimary: String,
    val primaryContainer: String,
    val onPrimaryContainer: String,
    val secondary: String,
    val onSecondary: String,
    val secondaryContainer: String,
    val onSecondaryContainer: String,
    val tertiary: String,
    val onTertiary: String,
    val tertiaryContainer: String,
    val onTertiaryContainer: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val surfaceVariant: String,
    val onSurfaceVariant: String,
    val surfaceContainerLowest: String,
    val surfaceContainerLow: String,
    val surfaceContainer: String,
    val surfaceContainerHigh: String,
    val surfaceContainerHighest: String,
    val surfaceDim: String,
    val surfaceBright: String,
    val outline: String,
    val outlineVariant: String
)

internal object ThemeTonePalettes {
    private val palettes = listOf(
        ThemeTonePalette(
            "Crimson", true, "#D0A7A3", "#2C1513",
            "#492E2B", "#F6CAC6", "#C1ACA9", "#251917",
            "#403130", "#E6CFCD", "#D0A7A3", "#2C1513",
            "#492E2B", "#F6CAC6", "#0C0C0E", "#E9E1DF",
            "#0C0C0E", "#E9E1DF", "#1E1E23", "#BEB7B6",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6F6969",
            "#3F3A3A"
        ),
        ThemeTonePalette(
            "Crimson", false, "#775653", "#FFFFFF",
            "#FFDAD6", "#2C1513", "#6B5A59", "#FFFFFF",
            "#F5DDDB", "#251917", "#775653", "#FFFFFF",
            "#FFDAD6", "#2C1513", "#FFF8F7", "#1E1B1A",
            "#FFF8F7", "#1E1B1A", "#E9E1DF", "#4A4645",
            "#FFFFFF", "#FAF2F0", "#F4ECEB", "#EFE6E5",
            "#E9E1DF", "#E0D8D7", "#FFF8F7", "#7B7675",
            "#CCC5C4"
        ),
        ThemeTonePalette(
            "Violet", true, "#B4ADC5", "#1D192B",
            "#373246", "#D9D0EA", "#B4AFB9", "#1D1A22",
            "#36333C", "#D8D2DD", "#B4ADC5", "#1D192B",
            "#373246", "#D9D0EA", "#0C0C0E", "#E6E1E3",
            "#0C0C0E", "#E6E1E3", "#1E1E23", "#BBB8BA",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6C6A6C",
            "#3C3B3D"
        ),
        ThemeTonePalette(
            "Violet", false, "#615B71", "#FFFFFF",
            "#E7DEF8", "#1D192B", "#605D66", "#FFFFFF",
            "#E6E0EC", "#1D1A22", "#615B71", "#FFFFFF",
            "#E7DEF8", "#1D192B", "#FDF8FA", "#1C1B1D",
            "#FDF8FA", "#1C1B1D", "#E6E1E3", "#484648",
            "#FFFFFF", "#F7F2F4", "#F1EDEE", "#EBE7E9",
            "#E6E1E3", "#DDD9DB", "#FDF8FA", "#797678",
            "#C9C5C7"
        ),
        ThemeTonePalette(
            "Ocean", true, "#9FB3C1", "#091E28",
            "#243742", "#C3D7E5", "#AAB1B7", "#161C21",
            "#2F363A", "#CED5DB", "#9FB3C1", "#091E28",
            "#243742", "#C3D7E5", "#0C0C0E", "#E4E2E3",
            "#0C0C0E", "#E4E2E3", "#1E1E23", "#B9B8B9",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6A6A6B",
            "#3B3B3C"
        ),
        ThemeTonePalette(
            "Ocean", false, "#4E616D", "#FFFFFF",
            "#D1E5F4", "#091E28", "#585F65", "#FFFFFF",
            "#DCE3E9", "#161C21", "#4E616D", "#FFFFFF",
            "#D1E5F4", "#091E28", "#FBF9F9", "#1B1C1C",
            "#FBF9F9", "#1B1C1C", "#E4E2E3", "#464747",
            "#FFFFFF", "#F5F3F4", "#EFEDEE", "#E9E8E8",
            "#E4E2E3", "#DBD9DA", "#FBF9F9", "#777778",
            "#C7C6C7"
        ),
        ThemeTonePalette(
            "Sage", true, "#A3B69F", "#101F10",
            "#293928", "#C7DAC2", "#ACB3A8", "#171D16",
            "#30372F", "#D0D6CB", "#A3B69F", "#101F10",
            "#293928", "#C7DAC2", "#0C0C0E", "#E4E2DF",
            "#0C0C0E", "#E4E2DF", "#1E1E23", "#BAB9B6",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6B6B68",
            "#3B3C3A"
        ),
        ThemeTonePalette(
            "Sage", false, "#526350", "#FFFFFF",
            "#D5E8D0", "#101F10", "#596057", "#FFFFFF",
            "#DEE5D9", "#171D16", "#526350", "#FFFFFF",
            "#D5E8D0", "#101F10", "#FBF9F6", "#1B1C1A",
            "#FBF9F6", "#1B1C1A", "#E4E2DF", "#474745",
            "#FFFFFF", "#F5F3F0", "#EFEEEA", "#EAE8E5",
            "#E4E2DF", "#DBDAD7", "#FBF9F6", "#777775",
            "#C8C6C3"
        ),
        ThemeTonePalette(
            "Amber", true, "#C5AD8C", "#261904",
            "#42321A", "#EBD0AE", "#BCAF9F", "#221A10",
            "#3C3328", "#E1D2C2", "#C5AD8C", "#261904",
            "#42321A", "#EBD0AE", "#0C0C0E", "#E8E1DD",
            "#0C0C0E", "#E8E1DD", "#1E1E23", "#BDB8B4",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6E6A67",
            "#3E3B38"
        ),
        ThemeTonePalette(
            "Amber", false, "#6F5B40", "#FFFFFF",
            "#F9DEBB", "#261904", "#675D50", "#FFFFFF",
            "#EFE0CF", "#221A10", "#6F5B40", "#FFFFFF",
            "#F9DEBB", "#261904", "#FFF8F4", "#1D1B19",
            "#FFF8F4", "#1D1B19", "#E8E1DD", "#494643",
            "#FFFFFF", "#F9F2EE", "#F3EDE8", "#EDE7E3",
            "#E8E1DD", "#DFD9D5", "#FFF8F4", "#7A7673",
            "#CBC5C1"
        ),
        ThemeTonePalette(
            "Rose", true, "#CDA7AD", "#2B151A",
            "#482E33", "#F3CAD1", "#BFACAE", "#24191B",
            "#3F3134", "#E4CFD2", "#CDA7AD", "#2B151A",
            "#482E33", "#F3CAD1", "#0C0C0E", "#E8E1E1",
            "#0C0C0E", "#E8E1E1", "#1E1E23", "#BEB7B7",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6E696A",
            "#3E3A3B"
        ),
        ThemeTonePalette(
            "Rose", false, "#75565C", "#FFFFFF",
            "#FFD9DF", "#2B151A", "#6A5A5D", "#FFFFFF",
            "#F3DDE0", "#24191B", "#75565C", "#FFFFFF",
            "#FFD9DF", "#2B151A", "#FFF8F7", "#1E1B1B",
            "#FFF8F7", "#1E1B1B", "#E8E1E1", "#4A4646",
            "#FFFFFF", "#FAF2F2", "#F4ECEC", "#EEE6E6",
            "#E8E1E1", "#E0D8D8", "#FFF8F7", "#7B7676",
            "#CCC5C5"
        ),
        ThemeTonePalette(
            "Mono", true, "#B0B0B0", "#1B1B1B",
            "#353535", "#D4D4D4", "#B0B0B0", "#1B1B1B",
            "#353535", "#D4D4D4", "#B0B0B0", "#1B1B1B",
            "#353535", "#D4D4D4", "#0C0C0E", "#E2E2E2",
            "#0C0C0E", "#E2E2E2", "#1E1E23", "#B9B9B9",
            "#070709", "#111113", "#161619", "#1C1C20",
            "#232328", "#0C0C0E", "#2A2A30", "#6A6A6A",
            "#3B3B3B"
        ),
        ThemeTonePalette(
            "Mono", false, "#5E5E5E", "#FFFFFF",
            "#E2E2E2", "#1B1B1B", "#5E5E5E", "#FFFFFF",
            "#E2E2E2", "#1B1B1B", "#5E5E5E", "#FFFFFF",
            "#E2E2E2", "#1B1B1B", "#F9F9F9", "#1B1B1B",
            "#F9F9F9", "#1B1B1B", "#E2E2E2", "#474747",
            "#FFFFFF", "#F3F3F3", "#EEEEEE", "#E8E8E8",
            "#E2E2E2", "#DADADA", "#F9F9F9", "#777777",
            "#C6C6C6"
        )
    ).associateBy { it.family to it.dark }

    fun get(family: String, dark: Boolean): ThemeTonePalette = palettes.getValue(family to dark)
}
