package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.guide.GuideStatement
import de.wanderlogplus.shared.guide.GuideStatements
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.StatementKind
import de.wanderlogplus.shared.sync.GuideCorrection
import de.wanderlogplus.shared.sync.TipVerdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What the fact check did to a guide: the corrected guide, its changes, and how many statements it researched. */
data class CheckOutcome(val guide: Guide, val corrections: List<GuideCorrection>, val researched: Int)

/** Sources kept per correction. */
private const val MAX_CORRECTION_SOURCES = 6

/**
 * Checks the statements of a guide for truth. Normally a first pass of [model] picks the doubtful ones (sweeping
 * rules that only hold in some places, exaggerations, outdated rules, prices and numbers, tourist myths) and only those
 * are researched; thorough checks research every statement. Research goes out in batches of [batchSize] statements per
 * [facts] call, which keeps Parallel runs few. Corrections are worked in right away; what stays unclear gets a hint.
 */
class GuideCheck(
    private val facts: FactSource,
    private val model: ChatModel,
    private val batchSize: Int = 8,
    private val maxSuspects: Int = 12,
) {
    /** [g] checked and corrected; [known] are facts already researched for the topic (they spare research). Throws [AiFailed]. */
    fun check(g: Guide, thorough: Boolean, known: Facts? = null, say: (String) -> Unit = {}): CheckOutcome {
        val all = GuideStatements.of(g)
        if (all.isEmpty()) return CheckOutcome(g, emptyList(), 0)
        val suspects = if (thorough) all.map { it to "" } else triage(g, all, known, say)
        if (suspects.isEmpty()) {
            say("Vorprüfung: nichts Zweifelhaftes gefunden")
            return CheckOutcome(g, emptyList(), 0)
        }
        val batches = suspects.chunked(batchSize.coerceAtLeast(1))
        say(
            (if (thorough) "Alle ${suspects.size} Aussagen" else "${suspects.size} zweifelhafte " + (if (suspects.size == 1) "Aussage" else "Aussagen")) +
                " werden in ${batches.size} " + (if (batches.size == 1) "Recherche" else "Recherchen") + " geprüft",
        )
        val verdicts = batches.flatMapIndexed { b, batch ->
            if (batches.size > 1) say("Recherche ${b + 1} von ${batches.size} …")
            judge(g, batch, research(g, batch, say) ?: return@flatMapIndexed emptyList<Pair<GuideStatement, Verdict>>().also { say("Keine Belege gefunden, diese Aussagen bleiben") }, say)
        }
        return apply(g, verdicts, suspects.size)
    }

    /** A judgement of one statement. */
    private data class Verdict(val verdict: TipVerdict, val text: String, val reason: String, val note: String, val sources: List<String>)

    /** The doubtful statements and why; statements with a hint are always looked at again. */
    private fun triage(g: Guide, all: List<GuideStatement>, known: Facts?, say: (String) -> Unit): List<Pair<GuideStatement, String>> {
        say("Vorprüfung: zweifelhafte Aussagen werden gesucht …")
        val system = """
            Du prüfst kritisch einen Guide einer Reise-App für Touristen in Japan. Viele Japan-Tipps im Netz sind abgeschrieben,
            übertrieben oder pauschal. Markiere jede Aussage, die nicht sicher und allgemein stimmt, besonders:
            - Pauschalregeln, die nur an bestimmten Orten, zu bestimmten Zeiten oder für bestimmte Leute gelten
              (eine Regel, die nur dort gilt, wo ein Schild sie verlangt, ist keine allgemeine Regel);
            - Übertreibungen („immer“, „nie“, „überall“, „verboten“, „sehr unhöflich“), wo es nur eine Gewohnheit ist;
            - Regeln, Preise, Zeiten, Zahlen, Apps und Verfahren, die sich geändert haben können;
            - verbreitete Touristen-Mythen.
            Nicht markieren: allgemein Bekanntes, das sicher stimmt, und was die recherchierten Themenfakten klar belegen.
            Höchstens $maxSuspects Aussagen, die zweifelhaftesten zuerst; keine, wenn alles stimmt.
            Antworte nur mit JSON: {"suspects":[{"i":3,"why":"kurz, warum zweifelhaft"}]}
        """.trimIndent()
        val user = buildString {
            append("Guide: ").append(g.title).append("\n\nAussagen:\n")
            all.forEachIndexed { i, s -> append(i).append(". [").append(label(s)).append("] ").append(s.text).append('\n') }
            known?.let { append("\nRecherchierte Themenfakten:\n").append(factLines(it)) }
        }
        val o = completeJson(model, system, user) { it["suspects"] is JsonArray }
        val picked = (o["suspects"] as JsonArray).mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val i = (x["i"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            all.getOrNull(i)?.let { it to x.text("why").orEmpty() }
        }.distinctBy { it.first }.take(maxSuspects)
        val noted = all.filter { GuideStatements.noteFor(g, it.text) != null && picked.none { p -> p.first == it } }.map { it to "trägt einen Hinweis" }
        return picked + noted
    }

    /** One research run for a whole batch: what is true about these statements, with sources. */
    private fun research(g: Guide, batch: List<Pair<GuideStatement, String>>, say: (String) -> Unit): Facts? = facts.facts(
        FactQuery(
            objective = buildString {
                append("Prüfe diese Aussagen eines Guides „").append(g.title).append("“ für Touristen in Japan, Stand heute. ")
                append("Gilt jede Aussage allgemein oder nur an bestimmten Orten oder in bestimmten Fällen? Ist sie übertrieben oder veraltet?\n")
                batch.forEachIndexed { i, (s, why) ->
                    append(i + 1).append(". ").append(s.text)
                    if (why.isNotBlank()) append(" (Zweifel: ").append(why).append(')')
                    append('\n')
                }
            },
            queries = batch.map { it.first.text }.sortedBy { it.length }.map { it.take(120) + " Japan" }.distinct().take(5),
            hints = listOf("Belege dafür und dagegen je Aussage", "Ausnahmen, Orte und Fälle, in denen sie gilt oder nicht", "aktueller Stand", "offizielle Quellen und Einheimische"),
        ),
        say,
    )

    /** The judgement of every statement in [batch] against the researched [found] facts. */
    private fun judge(g: Guide, batch: List<Pair<GuideStatement, String>>, found: Facts, say: (String) -> Unit): List<Pair<GuideStatement, Verdict>> {
        say("Aussagen werden beurteilt …")
        val system = """
            Du beurteilst Aussagen eines Guides einer Reise-App für Touristen in Japan streng nach den recherchierten Fakten.
            verdict je Aussage:
            TRUE = belegt und allgemein gültig; text leer lassen.
            PARTLY = im Kern richtig, aber pauschal, übertrieben oder ungenau; text = die richtige Fassung im Stil des Guides
              (gleiche Art und Länge: ein Do oder Don't bleibt ein kurzer Satz). Gilt etwas nur örtlich, sag das,
              statt es als allgemeine Regel zu formulieren.
            FALSE = falsch; text leer (die Aussage wird gestrichen) oder die richtige Aussage, wenn sie nützlich ist.
            UNVERIFIED = nicht belegbar oder die Quellen widersprechen sich; text = die Aussage, nötigenfalls vorsichtiger
              formuliert; note = ein bis zwei Sätze für den Hinweis in der App, warum es nicht eindeutig ist.
            reason: ein Satz, warum. sources: die URLs aus den Fakten, die das Urteil stützen.
            Kein Klammerzusatz und keine Quellenangabe im text.
            Antworte nur mit JSON: {"results":[{"i":0,"verdict":"TRUE|PARTLY|FALSE|UNVERIFIED","text":"","note":"","reason":"","sources":[]}]}
        """.trimIndent()
        val user = buildString {
            append("Guide: ").append(g.title).append("\n\nAussagen:\n")
            batch.forEachIndexed { i, (s, _) -> append(i).append(". [").append(label(s)).append("] ").append(s.text).append('\n') }
            append("\nRecherchierte Fakten:\n").append(factLines(found))
        }
        val o = completeJson(model, system, user, GERMAN_PLEASE) { it["results"] is JsonArray }
        val known = found.sources.toSet()
        return (o["results"] as JsonArray).mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            val st = (x["i"] as? JsonPrimitive)?.intOrNull?.let { batch.getOrNull(it)?.first } ?: return@mapNotNull null
            val verdict = TipVerdict.entries.firstOrNull { it.name == x.text("verdict")?.uppercase() } ?: return@mapNotNull null
            val sources = (x["sources"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { u -> u in known } }.distinct()
            st to Verdict(verdict, x.text("text").orEmpty(), x.text("reason").orEmpty(), x.text("note").orEmpty(), sources.ifEmpty { found.sources }.take(MAX_CORRECTION_SOURCES))
        }.distinctBy { it.first }
    }

    /** [g] with the verdicts worked in, and the changes made. */
    private fun apply(g: Guide, verdicts: List<Pair<GuideStatement, Verdict>>, researched: Int): CheckOutcome {
        val changes = mutableMapOf<GuideStatement, String>()
        val notes = mutableListOf<Pair<String, String>>()
        val cleared = mutableListOf<String>()
        val corrections = mutableListOf<GuideCorrection>()
        verdicts.forEach { (st, v) ->
            val after = when (v.verdict) {
                TipVerdict.TRUE -> st.text
                TipVerdict.PARTLY -> v.text.ifBlank { st.text }
                TipVerdict.FALSE -> v.text
                TipVerdict.UNVERIFIED -> v.text.ifBlank { st.text }
            }.trim()
            val note = if (v.verdict == TipVerdict.UNVERIFIED) v.note.ifBlank { v.reason } else ""
            if (note.isBlank()) cleared += after
            if (after == st.text && note.isBlank()) return@forEach
            if (after != st.text) changes[st] = after
            if (note.isNotBlank()) notes += after to note
            corrections += GuideCorrection(
                guideId = g.id, guideTitle = g.title, kind = st.kind, heading = st.heading, index = st.index,
                before = st.text, after = after, verdict = v.verdict, reason = v.reason, note = note, sources = v.sources,
            )
        }
        var out = GuideStatements.rewrite(g, changes)
        cleared.forEach { out = GuideStatements.withNote(out, it, "") }
        notes.forEach { (s, n) -> out = GuideStatements.withNote(out, s, n) }
        out = GuideStatements.pruneNotes(out)
        return CheckOutcome(out.copy(sources = (out.sources + corrections.flatMap { it.sources }).distinct()), corrections, researched)
    }

    private fun label(s: GuideStatement) = when (s.kind) {
        StatementKind.SUMMARY -> "Auf einen Blick"
        StatementKind.TEXT -> "${s.heading}: Kern"
        StatementKind.DO -> "${s.heading}: Do"
        StatementKind.DONT -> "${s.heading}: Don't"
        StatementKind.DETAILS -> "${s.heading}: Mehr dazu"
    }

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

/** "2 Korrekturen", "1 Hinweis", "alles stimmt" for a job's progress. */
fun correctionsText(cs: List<GuideCorrection>): String {
    val notes = cs.count { it.note.isNotBlank() && it.after == it.before }
    val fixed = cs.size - notes
    val parts = listOfNotNull(
        fixed.takeIf { it > 0 }?.let { if (it == 1) "1 Korrektur" else "$it Korrekturen" },
        notes.takeIf { it > 0 }?.let { if (it == 1) "1 Hinweis" else "$it Hinweise" },
    )
    return if (parts.isEmpty()) "alles stimmt" else parts.joinToString(", ")
}
