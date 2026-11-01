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

    @Test fun emptyTextHasNoBlocks() {
        assertEquals(emptyList<MdBlock>(), parseMarkdown(""))
    }
}
