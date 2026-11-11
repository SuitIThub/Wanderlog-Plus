package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.guide.GuideTopics
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.ReelTip
import de.wanderlogplus.shared.sync.TipVerdict
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Title of a new guide for a tip without a topic. */
private const val DEFAULT_TIP_GUIDE = "Reisetipps"

/** Sources kept per checked tip (the guide job takes at most 20 per addition). */
const val MAX_TIP_SOURCES = 8

/** Characters of a tip's guide text at most (the guide job takes at most 1000). */
const val MAX_TIP_TEXT = 1000

/** Characters of one guide in the digest at most. */
private const val DIGEST_PER_GUIDE = 1500

/**
 * Checks the travel tips of a reel or post: are they in a guide already ([match]), and are the new ones true
 * ([verify], [facts] researches, [model] judges). Instagram likes to exaggerate, so the judgement may correct a tip.
 */
class TipCheck(private val facts: FactSource, private val model: ChatModel, private val parallelism: Int = 3) {

    /** Each tip covered by a guide or with exactly one target; a failed answer makes every tip a new one. */
    fun match(tips: List<FoundTip>, guides: List<Guide>, say: (String) -> Unit = {}): List<ReelTip> {
        val fresh = tips.map { ReelTip(it.claim, it.topic, it.evidence, targetTitle = it.topic.ifBlank { DEFAULT_TIP_GUIDE }) }
        if (tips.isEmpty()) return fresh
        val system = """
            Du gleichst Reisetipps aus einem Instagram-Beitrag mit vorhandenen Guides einer Japan-Reise-App ab.
            Für jeden Tipp: Steht die Aussage inhaltlich schon in einem Guide? Dann covered true, guideId und ein kurzes Zitat (quote).
            Sonst covered false und genau ein Ziel: guideId eines passenden vorhandenen Guides, oder topic (key aus dem Themenkatalog,
            für den es noch keinen Guide gibt), oder title (Titel eines neuen Guides, kurz, auf Deutsch).
            Antworte nur mit JSON: {"tips":[{"i":0,"covered":false,"guideId":null,"quote":"","topic":null,"title":null}]}
        """.trimIndent()
        val user = buildString {
            append("Tipps:\n")
            tips.forEachIndexed { i, t -> append(i).append(". ").append(t.claim).append(" (Thema: ").append(t.topic.ifBlank { "?" }).append(")\n") }
            append("\nVorhandene Guides:\n").append(guideDigest(guides).ifBlank { "(keine)" })
            append("\n\nThemenkatalog:\n").append(GuideTopics.all.joinToString("\n") { "${it.key}: ${it.name}" })
        }
        val o = try {
            completeJson(model, system, user) { it["tips"] is JsonArray }
        } catch (e: Exception) {
            say("Abgleich mit den Guides fehlgeschlagen, alle Tipps gelten als neu")
            return fresh
        }
        val byId = guides.associateBy { it.id }
        val answers = (o["tips"] as JsonArray).mapNotNull { it as? JsonObject }.associateBy { (it["i"] as? JsonPrimitive)?.intOrNull ?: -1 }
        return fresh.mapIndexed { i, t ->
            val a = answers[i] ?: return@mapIndexed t
            val guideId = a.text("guideId")?.takeIf { it in byId }
            val covered = (a["covered"] as? JsonPrimitive)?.booleanOrNull == true
            when {
                covered && guideId != null -> t.copy(coveredBy = guideId, coveredQuote = a.text("quote").orEmpty(), targetTitle = null)
                guideId != null -> t.copy(guideId = guideId, targetTitle = null)
                a.text("topic")?.let { GuideTopics.byKey(it) } != null -> t.copy(targetTopic = a.text("topic"), targetTitle = null)
                a.text("title") != null -> t.copy(targetTitle = a.text("title"))
                else -> t
            }
        }
    }

    /** The tip with verdict, text for the guide, reason and sources; any failure gives UNVERIFIED. */
    fun verify(tip: ReelTip, say: (String) -> Unit = {}): ReelTip = try {
        val found = facts.facts(
            FactQuery(
                objective = "Stimmt das für Touristen in Japan, Stand heute? ${tip.claim}",
                queries = listOf(tip.claim, "${tip.claim} Japan"),
                hints = listOf("Belege dafür", "Belege dagegen", "Ausnahmen und Einschränkungen"),
            ),
            say,
        ) ?: return tip.copy(verdict = TipVerdict.UNVERIFIED, text = tip.claim.take(MAX_TIP_TEXT), reason = "Keine Belege gefunden")
        say("Tipp wird beurteilt …")
        val system = """
            Du prüfst einen Reisetipp aus einem Instagram-Beitrag für Japan-Reisende. Auf Instagram wird oft übertrieben oder Unsinn erzählt:
            urteile streng nach den recherchierten Fakten.
            verdict: TRUE = belegt; PARTLY = im Kern richtig, aber übertrieben oder ungenau; FALSE = falsch; UNVERIFIED = nicht belegbar.
            text: die Aussage so, wie sie in einen Guide gehört (bei PARTLY die korrigierte Fassung), ein bis zwei Sätze.
            reason: ein bis zwei Sätze, warum.
            Antworte nur mit JSON: {"verdict":"TRUE|PARTLY|FALSE|UNVERIFIED","text":"…","reason":"…"}
        """.trimIndent()
        val user = "Tipp: ${tip.claim}\n\nRecherchierte Fakten:\n" + factLines(found)
        val o = completeJson(model, system, user, GERMAN_PLEASE) { it.text("verdict") != null }
        val verdict = TipVerdict.entries.firstOrNull { it.name == o.text("verdict")?.uppercase() } ?: TipVerdict.UNVERIFIED
        tip.copy(
            verdict = verdict,
            text = (o.text("text") ?: tip.claim).take(MAX_TIP_TEXT),
            reason = o.text("reason").orEmpty(),
            sources = found.sources.take(MAX_TIP_SOURCES),
        )
    } catch (e: Exception) {
        tip.copy(verdict = TipVerdict.UNVERIFIED, text = tip.claim.take(MAX_TIP_TEXT), reason = "Prüfung fehlgeschlagen")
    }

    /** [match], then [verify] for every tip not covered yet, [parallelism] at a time, keeping the order. */
    fun checkAll(tips: List<FoundTip>, guides: List<Guide>, progress: JobProgress = JobProgress.NONE): List<ReelTip> {
        val matched = match(tips, guides) { progress.step(it, JobProgress.WARN) }
        val keyed = matched.mapIndexed { i, t -> "tip-$i" to t }
        progress.items(keyed.map { (k, t) -> JobItem(k, t.claim.take(60)) })
        keyed.filter { it.second.coveredBy != null }.forEach { (k, t) ->
            progress.item(k, state = "DONE", detail = "steht schon im Guide")
            progress.step("Tipp „${t.claim.take(60)}“ steht schon in einem Guide")
        }
        val todo = keyed.filter { it.second.coveredBy == null }
        if (todo.isEmpty()) return matched
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, todo.size)) { r -> Thread(r, "tip-check").apply { isDaemon = true } }
        try {
            val checked = pool.invokeAll(
                todo.map { (k, t) ->
                    Callable {
                        progress.item(k, state = "RUNNING")
                        val r = verify(t, progress.sayFor(k, "Tipp"))
                        progress.item(k, state = "DONE", detail = verdictText(r.verdict))
                        k to r
                    }
                },
            ).associate { it.get() }
            return keyed.map { (k, t) -> checked[k] ?: t }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

/** German label of a verdict for the job's progress. */
fun verdictText(v: TipVerdict?): String = when (v) {
    TipVerdict.TRUE -> "stimmt"
    TipVerdict.PARTLY -> "übertrieben, korrigiert"
    TipVerdict.FALSE -> "falsch"
    TipVerdict.UNVERIFIED, null -> "nicht belegt"
}

/** The guides in short for the model: id, title, topic, summary, then sections with their dos and don'ts. */
internal fun guideDigest(guides: List<Guide>): String = guides.joinToString("\n\n") { g ->
    buildString {
        append(g.id).append(" | ").append(g.title).append(" | ").append(g.topic ?: "-").append(" | ").append(g.summary)
        g.sections.forEach { s ->
            append("\n- ").append(s.heading)
            if (s.text.isNotBlank()) append(": ").append(s.text)
            s.dos.forEach { append("\n  + ").append(it) }
            s.donts.forEach { append("\n  - ").append(it) }
        }
    }.take(DIGEST_PER_GUIDE)
}
