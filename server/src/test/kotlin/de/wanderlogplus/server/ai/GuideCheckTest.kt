package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.guide.GuideStatements
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideSection
import de.wanderlogplus.shared.model.StatementKind
import de.wanderlogplus.shared.sync.TipVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuideCheckTest {
    private val guide = Guide(
        "g1", title = "Kleinigkeiten",
        summary = "Nicht im Gehen essen\nLeise in der Bahn",
        sections = listOf(
            GuideSection("Essen", text = "Gegessen wird am Stand.", donts = listOf("Nie im Gehen essen", "Nie Stäbchen in den Reis stecken")),
            GuideSection("Zahlen", dos = listOf("Immer Bargeld haben")),
        ),
    )

    private class Model(val triage: String, val judge: String) : ChatModel {
        val users = mutableListOf<String>()
        var judged = 0
        override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean): String {
            users += (parts.first() as AiPart.Text).text
            return if (system.contains("Markiere")) triage else judge.also { judged++ }
        }
    }

    private class CountingFacts : FactSource {
        val asked = mutableListOf<FactQuery>()
        override fun facts(q: FactQuery, say: (String) -> Unit): Facts {
            asked += q
            return Facts(listOf(Fact("Essen im Gehen ist nur an manchen Orten per Schild untersagt.", listOf("https://a.jp"))), listOf("https://a.jp", "https://b.jp"))
        }
    }

    @Test
    fun `only doubtful statements are researched, in one batch, and corrections are worked in`() {
        // Statements: 0 summary "Nicht im Gehen essen", 1 summary "Leise…", 2 text, 3 don't "Nie im Gehen", 4 don't Stäbchen, 5 do Bargeld
        val m = Model(
            triage = """{"suspects":[{"i":0,"why":"pauschal"},{"i":3,"why":"pauschal"},{"i":5,"why":"veraltet?"}]}""",
            judge = """{"results":[
                {"i":0,"verdict":"FALSE","text":"","reason":"Keine allgemeine Regel.","sources":["https://a.jp"]},
                {"i":1,"verdict":"PARTLY","text":"Wo ein Schild es verlangt, nicht im Gehen essen","reason":"Nur örtlich.","sources":["https://a.jp","https://evil.example"]},
                {"i":2,"verdict":"UNVERIFIED","text":"","note":"Karten werden immer öfter genommen, auf dem Land aber nicht überall.","reason":"Quellen uneins."}
            ]}""",
        )
        val facts = CountingFacts()
        val out = GuideCheck(facts, m).check(guide, thorough = false)
        assertEquals(1, facts.asked.size)
        assertTrue("Nie im Gehen essen" in facts.asked.single().objective)
        assertTrue("Stäbchen" !in facts.asked.single().objective)
        assertEquals("Leise in der Bahn", out.guide.summary)
        assertEquals(listOf("Wo ein Schild es verlangt, nicht im Gehen essen", "Nie Stäbchen in den Reis stecken"), out.guide.sections[0].donts)
        assertEquals("Karten werden immer öfter genommen, auf dem Land aber nicht überall.", GuideStatements.noteFor(out.guide, "Immer Bargeld haben"))
        assertEquals(3, out.corrections.size)
        val partly = out.corrections.single { it.verdict == TipVerdict.PARTLY }
        assertEquals(StatementKind.DONT, partly.kind)
        assertEquals("Essen", partly.heading)
        assertEquals("Nie im Gehen essen", partly.before)
        // Only sources from the research are kept.
        assertEquals(listOf("https://a.jp"), partly.sources)
        assertEquals("", out.corrections.single { it.verdict == TipVerdict.FALSE }.after)
    }

    @Test
    fun `thorough research batches every statement`() {
        val m = Model(triage = "unused", judge = """{"results":[]}""")
        val facts = CountingFacts()
        val out = GuideCheck(facts, m, batchSize = 4).check(guide, thorough = true)
        assertEquals(2, facts.asked.size)
        assertEquals(2, m.judged)
        assertTrue(m.users.none { "Markiere" in it })
        assertTrue(facts.asked.all { it.queries.size <= 5 })
        assertEquals(guide, out.guide)
        assertEquals(emptyList(), out.corrections)
    }

    @Test
    fun `nothing doubtful costs no research, and a true verdict clears an old hint`() {
        val facts = CountingFacts()
        assertEquals(0, GuideCheck(facts, Model("""{"suspects":[]}""", "")).check(guide, thorough = false).researched)
        assertTrue(facts.asked.isEmpty())
        val noted = GuideStatements.withNote(guide, "Immer Bargeld haben", "alt")
        // The hinted statement is looked at again although the first pass did not pick it.
        val out = GuideCheck(facts, Model("""{"suspects":[]}""", """{"results":[{"i":0,"verdict":"TRUE"}]}""")).check(noted, thorough = false)
        assertEquals(1, facts.asked.size)
        assertEquals(emptyList(), out.guide.notes)
        assertEquals(emptyList(), out.corrections)
    }

    @Test
    fun `corrections text`() {
        assertEquals("alles stimmt", correctionsText(emptyList()))
    }
}
