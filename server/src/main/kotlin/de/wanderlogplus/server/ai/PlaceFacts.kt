package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.PlaceDossier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Facts per place a request may carry, and places per request. */
const val MAX_FACTS_PER_PLACE = 12
const val MAX_FACT_PLACES = 20

/**
 * Works what a reel says about a saved place into its research: [model] sorts the facts into good-to-know, tips and
 * hints, leaves out what is there already and shortens each to one sentence. Old entries always stay.
 */
class PlaceFacts(private val model: ChatModel) {

    /** [old] (or a new dossier with researchedAt 0) with [facts] worked in; [AiFailed] "ai_bad_answer" when old entries would get lost. */
    fun merge(old: PlaceDossier?, placeName: String, facts: List<String>, sources: List<String>, say: (String) -> Unit = {}): PlaceDossier {
        say("Infos werden in die Recherche eingearbeitet …")
        val system = """
            Du ergänzt die Recherche zu einem Ort einer Japan-Reise-App um Aussagen aus einem Instagram-Beitrag.
            Sortiere jede neue Aussage in goodToKnow (Fakten), tips (was man tun sollte) oder hints (Warnungen).
            Lass weg, was inhaltlich schon dasteht. Jede neue Aussage als genau ein Satz auf Deutsch, nur die Aussage selbst,
            nichts über den Beitrag, die Person oder die Geschichte drumherum.
            Behalte alle bisherigen Einträge wörtlich und in ihrer Reihenfolge; neue kommen ans Ende der passenden Liste.
            Antworte nur mit JSON: {"goodToKnow": ["…"], "tips": ["…"], "hints": ["…"]}
        """.trimIndent()
        val user = buildString {
            append("Ort: ").append(placeName).append('\n')
            append("Bisher Gut zu wissen:\n").append(lines(old?.goodToKnow)).append('\n')
            append("Bisher Tipps:\n").append(lines(old?.tips)).append('\n')
            append("Bisher Hinweise:\n").append(lines(old?.hints)).append('\n')
            append("\nNeue Aussagen aus dem Beitrag:\n").append(lines(facts))
        }
        val o = completeJson(model, system, user, GERMAN_PLEASE) { keepsDossier(old, parsed(it, old)) }
        return parsed(o, old).copy(
            reelFacts = (old?.reelFacts.orEmpty() + facts).distinct(),
            sources = (old?.sources.orEmpty() + sources).distinct(),
        )
    }

    private fun lines(l: List<String>?) = l.orEmpty().joinToString("\n") { "- $it" }.ifBlank { "(nichts)" }

    private fun parsed(o: JsonObject, old: PlaceDossier?) = (old ?: PlaceDossier()).copy(
        goodToKnow = o.strings("goodToKnow"),
        tips = o.strings("tips"),
        hints = o.strings("hints"),
    )

    private fun JsonObject.strings(k: String) =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
}

/** True when every good-to-know, tip and hint of [before] is still in [after]. */
internal fun keepsDossier(before: PlaceDossier?, after: PlaceDossier): Boolean =
    before == null || (after.goodToKnow.containsAll(before.goodToKnow) && after.tips.containsAll(before.tips) && after.hints.containsAll(before.hints))
