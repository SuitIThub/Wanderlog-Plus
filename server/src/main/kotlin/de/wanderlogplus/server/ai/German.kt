package de.wanderlogplus.server.ai

private val GERMAN_WORDS = setOf(
    "der", "die", "das", "und", "mit", "ist", "ein", "eine", "einer", "einem", "den", "dem", "des", "im", "am", "zum", "zur",
    "für", "auf", "von", "nicht", "sich", "auch", "sind", "wird", "bei", "nach", "oder", "aus", "wie", "man", "fast", "sehr",
)
private val ENGLISH_WORDS = setOf(
    "the", "and", "with", "is", "a", "an", "of", "for", "in", "on", "to", "from", "this", "that", "are", "its", "it", "their",
    "offering", "you", "your", "can", "be", "by", "as", "at", "which", "while",
)

/**
 * Whether [text] reads as German: German umlauts or more German than English small words; Japanese (mostly kana or
 * kanji) is not German. Empty text counts as German (nothing to translate).
 */
fun looksGerman(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return true
    if (mostlyJapanese(t)) return false
    val words = t.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
    val de = words.count { it in GERMAN_WORDS } + (if (Regex("[äöüß]").containsMatchIn(t.lowercase())) 2 else 0)
    val en = words.count { it in ENGLISH_WORDS }
    return de >= en
}

/** Research may replace a place's description when there is none or the old one is not German, and the new one is. */
fun replacesDescription(existing: String?, researched: String): Boolean =
    researched.isNotBlank() && looksGerman(researched) && (existing.isNullOrBlank() || !looksGerman(existing))
