package com.example.data.local

import java.text.Normalizer

/** Romanize Indian-script labels when the provider has no label in the requested script.
 * Apply only at presentation boundaries. Search, playback matching, and storage keep the original text.
 */
internal object CatalogDisplayText {
    private val vowels = mapOf(0x05 to "a", 0x06 to "aa", 0x07 to "i", 0x08 to "ee", 0x09 to "u",
        0x0A to "oo", 0x0B to "ri", 0x0D to "e", 0x0F to "e", 0x10 to "ai", 0x11 to "o",
        0x13 to "o", 0x14 to "au")
    private val marks = mapOf(0x3E to "aa", 0x3F to "i", 0x40 to "ee", 0x41 to "u", 0x42 to "oo",
        0x43 to "ri", 0x44 to "ree", 0x45 to "e", 0x46 to "e", 0x47 to "e", 0x48 to "ai",
        0x49 to "o", 0x4A to "o", 0x4B to "o", 0x4C to "au")
    private val consonants = listOf("k", "kh", "g", "gh", "ng", "ch", "chh", "j", "jh", "ny", "t", "th", "d",
        "dh", "n", "t", "th", "d", "dh", "n", "n", "p", "ph", "b", "bh", "m", "y", "r", "r", "l", "l",
        "l", "v", "sh", "sh", "s", "h")
    private val dotted = mapOf(0x15 to "q", 0x16 to "kh", 0x17 to "gh", 0x1C to "z", 0x21 to "r",
        0x22 to "rh", 0x2B to "f", 0x2F to "y")
    private val punjabiExtras = mapOf(0x59 to "kh", 0x5A to "gh", 0x5B to "z", 0x5C to "r", 0x5E to "f")
    private val scripts = Regex("[\\u0900-\\u097f]+|[\\u0a00-\\u0a7f]+")

    fun inLanguage(value: String, language: String): String = scripts.replace(value) { run ->
        val base = if (run.value.first().code < 0x0980) 0x0900 else 0x0A00
        if (language == if (base == 0x0900) "hi" else "pa") run.value
        else romanize(run.value, base).replaceFirstChar { it.uppercaseChar() }
    }

    private fun romanize(word: String, base: Int): String = buildString(word.length * 2) {
        // Unicode normalization splits most precomposed dotted consonants.
        val chars = Normalizer.normalize(word, Normalizer.Form.NFD)
        var index = 0
        var doubleNext = false
        while (index < chars.length) {
            val glyph = chars[index++].code - base
            val consonant = consonants.getOrNull(glyph - 0x15)
                ?: punjabiExtras[glyph].takeIf { base == 0x0A00 }
            if (consonant != null) {
                val dottedGlyph = chars.getOrNull(index)?.code == base + 0x3C
                if (dottedGlyph) index++
                val sound = if (dottedGlyph) dotted[glyph] ?: consonant else consonant
                if (doubleNext) append(sound.first())
                doubleNext = false
                val next = chars.getOrNull(index)?.code?.minus(base)
                append(sound)
                when {
                    next == 0x4D -> index++ // virama suppresses the inherent vowel
                    next != null && next in marks -> { append(marks.getValue(next)); index++ }
                    index < chars.length || length == sound.length -> append('a')
                }
                continue
            }
            when (glyph) {
                0x01, 0x02, 0x70 -> append('n')
                0x03 -> append('h')
                0x71 -> doubleNext = true
                0x3C, 0x4D -> Unit
                in 0x66..0x6F -> append(glyph - 0x66)
                else -> append(vowels[glyph] ?: marks[glyph] ?: chars[index - 1].toString())
            }
        }
    }
}
