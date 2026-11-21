package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideNote
import de.wanderlogplus.shared.model.GuideSection
import de.wanderlogplus.shared.model.StatementKind
import de.wanderlogplus.shared.sync.GuideCorrection
import de.wanderlogplus.shared.sync.TipVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuideStatementsTest {
    private val g = Guide(
        id = "g1",
        summary = "• Leise sein\nNicht im Gehen essen",
        sections = listOf(
            GuideSection("Essen", text = "Gegessen wird am Stand.", dos = listOf("Am Stand essen"), donts = listOf("Nie im Gehen essen"), details = "Hintergrund."),
            GuideSection("Zahlen", dos = listOf("Bar mitnehmen")),
        ),
    )

    @Test
    fun `lists every statement in reading order without bullets`() {
        val all = GuideStatements.of(g)
        assertEquals(listOf("Leise sein", "Nicht im Gehen essen", "Gegessen wird am Stand.", "Am Stand essen", "Nie im Gehen essen", "Hintergrund.", "Bar mitnehmen"), all.map { it.text })
        assertEquals(StatementKind.DONT, all[4].kind)
        assertEquals("Essen", all[4].heading)
    }

    @Test
    fun `rewrite replaces and removes, drops empty sections and orphaned notes`() {
        val all = GuideStatements.of(g)
        val noted = g.copy(notes = listOf(GuideNote("Bar mitnehmen", "unklar"), GuideNote("Leise sein", "x")))
        val out = GuideStatements.rewrite(noted, mapOf(all[1] to "", all[4] to "Wo Schilder es verbieten, nicht im Gehen essen", all[6] to ""))
        assertEquals("Leise sein", out.summary)
        assertEquals(listOf("Wo Schilder es verbieten, nicht im Gehen essen"), out.sections[0].donts)
        assertEquals(1, out.sections.size)
        assertEquals(listOf(GuideNote("Leise sein", "x")), out.notes)
    }

    @Test
    fun `revert brings a corrected statement back and removes its hint`() {
        val all = GuideStatements.of(g)
        val fixed = GuideStatements.withNote(GuideStatements.rewrite(g, mapOf(all[4] to "Wo Schilder stehen, nicht im Gehen essen")), "Wo Schilder stehen, nicht im Gehen essen", "Quellen uneins")
        val c = GuideCorrection("g1", kind = StatementKind.DONT, heading = "Essen", index = 0, before = "Nie im Gehen essen", after = "Wo Schilder stehen, nicht im Gehen essen", verdict = TipVerdict.PARTLY)
        assertEquals(CorrectionState.APPLIED, GuideStatements.stateOf(fixed, c))
        val back = GuideStatements.revert(fixed, c)!!
        assertEquals(listOf("Nie im Gehen essen"), back.sections[0].donts)
        assertEquals(emptyList(), back.notes)
        assertEquals(CorrectionState.REVERTED, GuideStatements.stateOf(back, c))
        assertNull(GuideStatements.revert(back, c))
    }

    @Test
    fun `revert of a removal puts it back, re-creating the section`() {
        val all = GuideStatements.of(g)
        val removed = GuideStatements.rewrite(g, mapOf(all[6] to "", all[1] to ""))
        val doBack = GuideCorrection("g1", kind = StatementKind.DO, heading = "Zahlen", index = 0, before = "Bar mitnehmen", verdict = TipVerdict.FALSE)
        assertEquals(listOf("Bar mitnehmen"), GuideStatements.revert(removed, doBack)!!.sections.last().dos)
        val lineBack = GuideCorrection("g1", kind = StatementKind.SUMMARY, index = 1, before = "Nicht im Gehen essen", verdict = TipVerdict.FALSE)
        assertEquals("Leise sein\nNicht im Gehen essen", GuideStatements.revert(removed, lineBack)!!.summary)
    }

    @Test
    fun `a hint-only correction is taken back by removing the hint, and is changed once the text is edited`() {
        val noted = GuideStatements.withNote(g, "Bar mitnehmen", "Viele Läden nehmen inzwischen Karte")
        val c = GuideCorrection("g1", kind = StatementKind.DO, heading = "Zahlen", before = "Bar mitnehmen", after = "Bar mitnehmen", verdict = TipVerdict.UNVERIFIED, note = "Viele Läden nehmen inzwischen Karte")
        assertEquals("Viele Läden nehmen inzwischen Karte", GuideStatements.noteFor(noted, "Bar mitnehmen"))
        assertEquals(emptyList(), GuideStatements.revert(noted, c)!!.notes)
        val edited = noted.copy(sections = listOf(g.sections[0], GuideSection("Zahlen", dos = listOf("Etwas Bargeld mitnehmen"))))
        assertEquals(CorrectionState.CHANGED, GuideStatements.stateOf(edited, c))
        assertEquals(emptyList(), GuideStatements.pruneNotes(edited).notes)
    }
}
