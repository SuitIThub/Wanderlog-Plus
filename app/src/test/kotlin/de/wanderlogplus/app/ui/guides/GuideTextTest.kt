package de.wanderlogplus.app.ui.guides

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideTextTest {
    @Test fun glanceLinesFromBulletsAndPlainText() {
        assertEquals(listOf("A", "B", "C", "D"), glanceLines("• A\n- B\n\n C \n* D"))
        assertEquals(listOf("Ein Satz."), glanceLines("Ein Satz."))
        assertEquals(emptyList<String>(), glanceLines("  \n "))
    }

    @Test fun coreLineIsTheFirstSentence() {
        assertEquals("Erst waschen.", coreLine("Erst waschen. Dann baden."))
        assertEquals("Wirklich?", coreLine("Wirklich? Ja."))
        assertEquals("Ohne Punkt", coreLine("Ohne Punkt"))
        assertEquals("Ca. 5 Min.", coreLine("Ca. 5 Min."))
    }
}
