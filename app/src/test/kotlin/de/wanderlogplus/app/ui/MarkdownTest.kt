package de.wanderlogplus.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun boldThenPlain() {
        val b = parseMarkdown("**Alternative:** Bus").single() as MdBlock.Paragraph
        assertEquals(listOf(MdSpan("Alternative:", bold = true), MdSpan(" Bus")), b.spans)
    }

    @Test fun bullets() {
        val blocks = parseMarkdown("- a\n- b")
        assertEquals(listOf(MdBlock.Bullet(listOf(MdSpan("a"))), MdBlock.Bullet(listOf(MdSpan("b")))), blocks)
    }

    @Test fun softBreakStaysInOneParagraph() {
        val b = parseMarkdown("Zeile 1\nZeile 2").single() as MdBlock.Paragraph
        assertEquals(listOf(MdSpan("Zeile 1\nZeile 2")), b.spans)
    }

    @Test fun blankLineSeparatesParagraphs() {
        assertEquals(2, parseMarkdown("A\n\nB").size)
    }

    @Test fun numbered() {
        assertEquals(listOf(MdBlock.Numbered(1, listOf(MdSpan("x")))), parseMarkdown("1. x"))
    }

    @Test fun italicAndLink() {
        val b = parseMarkdown("*kursiv* und [Karte](https://example.org/a?b=1)").single()
        assertEquals(
            listOf(MdSpan("kursiv", italic = true), MdSpan(" und "), MdSpan("Karte", link = "https://example.org/a?b=1")),
            b.spans,
        )
    }

    @Test fun unicodeAndUnmatchedMarkersUnchanged() {
        val b = parseMarkdown("Café ⭐ Müßli 2*3 [x").single()
        assertEquals(listOf(MdSpan("Café ⭐ Müßli 2*3 [x")), b.spans)
    }

    @Test fun onlyWebLinksAreSafe() {
        assertEquals(true, isSafeUrl("https://example.org"))
        assertEquals(true, isSafeUrl(" HTTP://example.org"))
        assertEquals(false, isSafeUrl("javascript:alert(1)"))
        assertEquals(false, isSafeUrl("intent://x#Intent;end"))
        assertEquals(false, isSafeUrl("file:///etc/passwd"))
        assertEquals(false, isSafeUrl("tel:123"))
    }

    @Test fun emptyTextHasNoBlocks() {
        assertEquals(emptyList<MdBlock>(), parseMarkdown(""))
    }
}
