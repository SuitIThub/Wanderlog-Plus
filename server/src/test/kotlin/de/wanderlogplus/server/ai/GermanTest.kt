package de.wanderlogplus.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GermanTest {
    @Test
    fun `german and other texts`() {
        assertTrue(looksGerman("Ein stiller Tempel mit Moosgarten, der am frühen Morgen fast leer ist."))
        assertTrue(looksGerman("Ramen mit klarer Brühe und Wartezeit."))
        assertFalse(looksGerman("Yuamiya is a beloved spot in Gero, offering a unique experience of enjoying hot spring water."))
        assertFalse(looksGerman("京都の人気ラーメン店です。"))
        assertTrue(looksGerman(""))
    }

    @Test
    fun `research replaces only a description that is not German`() {
        val german = "Ein stiller Tempel mit Moosgarten."
        assertTrue(replacesDescription(null, german))
        assertTrue(replacesDescription("", german))
        assertTrue(replacesDescription("A quiet temple with a moss garden and the oldest pagoda in the city.", german))
        assertFalse(replacesDescription("Eine eigene Beschreibung, die bleibt.", german))
        assertFalse(replacesDescription("A quiet temple with a moss garden.", "A quiet temple."))
    }

    @Test
    fun `the research asks for German`() {
        assertTrue("auf Deutsch" in RESEARCH_JSON_SHAPE.substringBefore("\"category\""))
        assertEquals(true, RESEARCH_JSON_SHAPE.contains("Alle Texte auf Deutsch"))
    }
}
