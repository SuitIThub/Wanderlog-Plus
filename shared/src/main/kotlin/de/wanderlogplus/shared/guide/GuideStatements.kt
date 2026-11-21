package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideNote
import de.wanderlogplus.shared.model.GuideSection
import de.wanderlogplus.shared.model.StatementKind
import de.wanderlogplus.shared.sync.GuideCorrection

/**
 * One statement of a guide the fact check looks at: a summary line, a section's core text, a do, a don't or the
 * details. [section] is the section's position (-1 for the summary), [index] the line or item within it.
 */
data class GuideStatement(val kind: StatementKind, val section: Int, val heading: String, val index: Int, val text: String)

/** Whether a correction is still in the guide, was taken back, or the statement was changed since. */
enum class CorrectionState { APPLIED, REVERTED, CHANGED }

/** The statements of a guide, rewriting them, the hints on them (⚠) and taking a correction back. */
object GuideStatements {
    /** The summary as lines, without bullet marks or empty lines. */
    fun summaryLines(summary: String): List<String> =
        summary.lines().map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }.filter { it.isNotEmpty() }

    /** Every statement in reading order; phrases are language, not claims, and are left out. */
    fun of(g: Guide): List<GuideStatement> = buildList {
        summaryLines(g.summary).forEachIndexed { i, l -> add(GuideStatement(StatementKind.SUMMARY, -1, "", i, l)) }
        g.sections.forEachIndexed { si, s ->
            if (s.text.isNotBlank()) add(GuideStatement(StatementKind.TEXT, si, s.heading, 0, s.text.trim()))
            s.dos.forEachIndexed { i, d -> if (d.isNotBlank()) add(GuideStatement(StatementKind.DO, si, s.heading, i, d.trim())) }
            s.donts.forEachIndexed { i, d -> if (d.isNotBlank()) add(GuideStatement(StatementKind.DONT, si, s.heading, i, d.trim())) }
            if (s.details.isNotBlank()) add(GuideStatement(StatementKind.DETAILS, si, s.heading, 0, s.details.trim()))
        }
    }

    /**
     * [g] with the statements in [changes] replaced by their new text (blank: removed). Sections left without any
     * content disappear; hints on statements that no longer exist are dropped.
     */
    fun rewrite(g: Guide, changes: Map<GuideStatement, String>): Guide {
        if (changes.isEmpty()) return g
        fun new(st: GuideStatement): String = changes[st]?.trim() ?: st.text
        val all = of(g)
        val summary = all.filter { it.kind == StatementKind.SUMMARY }.map(::new).filter { it.isNotBlank() }.joinToString("\n")
        val sections = g.sections.mapIndexedNotNull { si, s ->
            fun one(kind: StatementKind, old: String) = all.firstOrNull { it.section == si && it.kind == kind }?.let(::new) ?: old
            fun list(kind: StatementKind, old: List<String>) = old.mapIndexed { i, d ->
                all.firstOrNull { it.section == si && it.kind == kind && it.index == i }?.let(::new) ?: d
            }.filter { it.isNotBlank() }
            val after = s.copy(
                text = one(StatementKind.TEXT, s.text),
                dos = list(StatementKind.DO, s.dos),
                donts = list(StatementKind.DONT, s.donts),
                details = one(StatementKind.DETAILS, s.details),
            )
            after.takeUnless { empty(it) }
        }
        return pruneNotes(g.copy(summary = summary, sections = sections))
    }

    private fun empty(s: GuideSection) = s.text.isBlank() && s.details.isBlank() && s.dos.isEmpty() && s.donts.isEmpty()

    /** [g] without hints whose statement is gone (edited or removed), and at most one hint per statement. */
    fun pruneNotes(g: Guide): Guide {
        if (g.notes.isEmpty()) return g
        val texts = of(g).map { it.text }.toSet()
        val kept = g.notes.filter { it.statement.trim() in texts && it.note.isNotBlank() }.distinctBy { it.statement.trim() }
        return if (kept == g.notes) g else g.copy(notes = kept)
    }

    /** The hint on the statement with this wording, or null. */
    fun noteFor(g: Guide, text: String): String? = g.notes.firstOrNull { it.statement.trim() == text.trim() }?.note?.takeIf { it.isNotBlank() }

    /** [g] with [note] on the statement [statement] (replacing an older one; blank removes it). */
    fun withNote(g: Guide, statement: String, note: String): Guide {
        val rest = g.notes.filter { it.statement.trim() != statement.trim() }
        return g.copy(notes = if (note.isBlank()) rest else rest + GuideNote(statement.trim(), note.trim()))
    }

    private fun has(g: Guide, kind: StatementKind, text: String) = of(g).any { it.kind == kind && it.text == text.trim() }

    /** Whether [c] is still in [g], was taken back, or the statement has changed since. */
    fun stateOf(g: Guide, c: GuideCorrection): CorrectionState = when {
        c.after.isBlank() -> if (has(g, c.kind, c.before)) CorrectionState.REVERTED else CorrectionState.APPLIED
        c.after.trim() == c.before.trim() -> when {
            !has(g, c.kind, c.after) -> CorrectionState.CHANGED
            noteFor(g, c.after) != null -> CorrectionState.APPLIED
            else -> CorrectionState.REVERTED
        }
        has(g, c.kind, c.after) -> CorrectionState.APPLIED
        has(g, c.kind, c.before) -> CorrectionState.REVERTED
        else -> CorrectionState.CHANGED
    }

    /**
     * [g] with [c] taken back: the old wording again (a removed statement goes back to where it was, its section
     * re-created when needed) and the hint gone. Null when [c] is no longer applied ([stateOf]).
     */
    fun revert(g: Guide, c: GuideCorrection): Guide? {
        if (stateOf(g, c) != CorrectionState.APPLIED) return null
        if (c.after.isNotBlank()) {
            val target = of(g).filter { it.kind == c.kind && it.text == c.after.trim() }.let { m -> m.firstOrNull { it.heading == c.heading } ?: m.first() }
            val noteless = withNote(g, c.after, "")
            if (c.after.trim() == c.before.trim()) return noteless
            return rewrite(noteless, mapOf(target to c.before))
        }
        if (c.kind == StatementKind.SUMMARY) {
            val lines = summaryLines(g.summary).toMutableList()
            lines.add(c.index.coerceIn(0, lines.size), c.before.trim())
            return g.copy(summary = lines.joinToString("\n"))
        }
        val at = g.sections.indexOfFirst { it.heading == c.heading }
        val section = if (at >= 0) g.sections[at] else GuideSection(c.heading)
        fun insert(list: List<String>) = list.toMutableList().apply { add(c.index.coerceIn(0, size), c.before.trim()) }
        val back = when (c.kind) {
            StatementKind.TEXT -> section.copy(text = listOf(section.text, c.before).filter { it.isNotBlank() }.joinToString(" "))
            StatementKind.DETAILS -> section.copy(details = listOf(section.details, c.before).filter { it.isNotBlank() }.joinToString("\n\n"))
            StatementKind.DO -> section.copy(dos = insert(section.dos))
            StatementKind.DONT -> section.copy(donts = insert(section.donts))
            StatementKind.SUMMARY -> section
        }
        return g.copy(sections = if (at >= 0) g.sections.toMutableList().apply { set(at, back) } else g.sections + back)
    }
}
