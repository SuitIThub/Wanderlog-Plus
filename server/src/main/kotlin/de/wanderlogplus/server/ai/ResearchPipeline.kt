package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.sync.AssessConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Asks [model] for a JSON object; asks once more when the answer has none or [valid] rejects it, then fails with "ai_bad_answer". */
fun completeJson(model: ChatModel, system: String, user: String, valid: (JsonObject) -> Boolean = { true }): JsonObject {
    repeat(2) {
        val o = try {
            OpenRouterModel.jsonObjectIn(model.complete(system, listOf(AiPart.Text(user)), webSearch = false))
        } catch (e: AiFailed) {
            if (e.code != "ai_bad_answer") throw e
            null
        }
        if (o != null && valid(o)) return o
    }
    throw AiFailed("ai_bad_answer")
}

/** The facts as numbered prompt lines with their sources. */
internal fun factLines(f: Facts): String = f.items.withIndex().joinToString("\n") { (i, x) ->
    "${i + 1}. ${x.text}" + (x.sources.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: "")
}

/** Place research in two steps: [facts] gathers what is known, [model] judges it for the trip and writes the dossier. */
class PipelineResearcher(private val facts: FactSource, private val model: ChatModel) : PlaceResearcher {
    override fun research(query: ResearchQuery, config: AssessConfig, say: (String) -> Unit): ResearchOutcome? {
        val label = listOfNotNull(query.name, query.localName?.let { "($it)" }, query.city).joinToString(" ")
        val found = facts.facts(
            FactQuery(
                objective = "Bewertungen, Tipps und praktische Hinweise zu $label für Reisende",
                queries = listOfNotNull(listOfNotNull(query.name, query.city).joinToString(" "), query.localName, "${query.name} tips reviews"),
                hints = listOf(
                    "Öffnungszeiten, Reservierung, Andrang, Preise",
                    "Geheimtipps von Einheimischen und Kennern, versteckte Ecken in der Nähe",
                    "zugehörige Punkte mit Lage (weitere Eingänge, Aussichtspunkte, nächster Bahnhof)",
                    "was man vorher wissen sollte (Etikette, Zahlung, Regeln)",
                ),
            ),
            say,
        ) ?: run { say("Recherche ohne Ergebnis"); return null }
        say("${found.items.size} Fakten gefunden, Bewertung durch das Textmodell …")
        val system = """
            Du bewertest für eine Reise-App einen Ort anhand recherchierter Fakten und schreibst sachlich auf Deutsch, wie ein guter Reiseführer.
            Behaupte nichts, was nicht in den Fakten steht, außer allgemein Bekanntem. Beziehe den Reisekontext ein (Region, was dort schon geplant ist).
            ${criteriaPrompt(config)}
            Antworte nur mit JSON: $RESEARCH_JSON_SHAPE
        """.trimIndent()
        val user = buildString {
            append(researchPrompt(query))
            if (query.tripContext.isNotBlank()) append("\nReisekontext: ").append(query.tripContext)
            append("\n\nRecherchierte Fakten:\n").append(factLines(found))
        }
        val o = completeJson(model, system, user) { (it["verdict"] as? JsonPrimitive)?.content?.isNotBlank() == true && it["worth"] is JsonPrimitive }
        return parseResearch(o, config, found.sources)
    }
}
