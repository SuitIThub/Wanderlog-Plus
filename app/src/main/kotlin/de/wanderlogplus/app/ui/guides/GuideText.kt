package de.wanderlogplus.app.ui.guides

/** The "Auf einen Blick" points of a guide summary: one per line, bullets and blank lines dropped. */
fun glanceLines(summary: String): List<String> =
    summary.lines().map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }.filter { it.isNotEmpty() }

private val SENTENCE_END = Regex("""(?<=[.!?])\s+(?=[A-ZÄÖÜ])""")

/** The first sentence of a section's core text (shown while the section is folded); the whole text without a sentence end. */
fun coreLine(text: String): String = text.trim().split(SENTENCE_END, limit = 2).first()
