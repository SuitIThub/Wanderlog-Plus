package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.sync.AssessConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Asks [model] for a JSON object; asks once more (with [retryHint] added) when the answer has none or [valid] rejects
 * it, then fails with "ai_bad_answer".
 */
fun completeJson(model: ChatModel, system: String, user: String, retryHint: String = "", valid: (JsonObject) -> Boolean = { true }): JsonObject {
    repeat(2) { attempt ->
        val text = if (attempt == 0 || retryHint.isBlank()) user else user + "\n\n" + retryHint
        val o = try {
            OpenRouterModel.jsonObjectIn(model.complete(system, listOf(AiPart.Text(text)), webSearch = false))
        } catch (e: AiFailed) {
            if (e.code != "ai_bad_answer") throw e
            null
        }
        if (o != null && valid(o)) return o
    }
    throw AiFailed("ai_bad_answer")
}

/** True when most letters of [text] are Japanese (kana or kanji); Japanese names inside German text are fine. */
fun mostlyJapanese(text: String): Boolean {
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty()) return false
    val japanese = letters.count { c ->
        val b = Character.UnicodeBlock.of(c)
        b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            b == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }
    return japanese * 2 > letters.length
}

/** Added to a second request when the first answer came back in Japanese. */
internal const val GERMAN_PLEASE = "WICHTIG: Schreibe alle Texte auf Deutsch, nicht auf Japanisch. Japanische Namen nur in Klammern hinter dem deutschen oder lateinischen Namen."

/** The facts as numbered prompt lines with their sources. */
internal fun factLines(f: Facts): String = f.items.withIndex().joinToString("\n") { (i, x) ->
    "${i + 1}. ${x.text}" + (x.sources.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: "")
}

/** Place research in two steps: [facts] gathers what is known, [model] judges it for the trip and writes the dossier. */
class PipelineResearcher(
    private val facts: FactSource,
    private val model: ChatModel,
    /** Google's facts (hours, rating, price, contact) for a Google place id; none without a Places key. */
    private val google: (String) -> List<Fact> = { emptyList() },
) : PlaceResearcher {
    override fun research(query: ResearchQuery, config: AssessConfig, say: (String) -> Unit): ResearchOutcome? {
        val label = listOfNotNull(query.name, query.localName?.let { "($it)" }, query.city).joinToString(" ")
        val found = facts.facts(
            FactQuery(
                // What the reel says (district, nearest station) picks the right branch.
                objective = "Bewertungen, Tipps und praktische Hinweise zu $label für Reisende" +
                    (query.evidence.takeIf { it.isNotBlank() }?.let { ". Bekannt: $it" } ?: ""),
                queries = listOfNotNull(listOfNotNull(query.name, query.city).joinToString(" "), query.localName, "${query.name} tips reviews"),
                hints = listOf(
                    "Öffnungszeiten, Reservierung, Andrang, Preise",
                    "Geheimtipps von Einheimischen und Kennern, versteckte Ecken in der Nähe",
                    "zugehörige Punkte mit Lage (weitere Eingänge, Aussichtspunkte, nächster Bahnhof)",
                    "was man vorher wissen sollte (Etikette, Zahlung, Regeln)",
                    "ob es mehrere Filialen gibt; welche zu den bekannten Hinweisen (Stadtteil, Bahnhof) passt und wo die anderen in der Stadt liegen",
                ),
            ),
            say,
        )
        val fromGoogle = query.googlePlaceId?.let { id -> try { google(id) } catch (e: Exception) { emptyList() } }.orEmpty()
        if (fromGoogle.isNotEmpty()) say("${fromGoogle.size} Angaben von Google (Öffnungszeiten, Bewertung, Preis)")
        val all = when {
            found == null && fromGoogle.isEmpty() -> { say("Recherche ohne Ergebnis"); return null }
            found == null -> Facts(fromGoogle, fromGoogle.flatMap { it.sources }.distinct())
            else -> Facts(fromGoogle + found.items, (fromGoogle.flatMap { it.sources } + found.sources).distinct())
        }
        say("${all.items.size} Fakten gefunden, Bewertung durch das Textmodell …")
        val system = """
            Du bewertest für eine Reise-App einen Ort anhand recherchierter Fakten und schreibst sachlich auf Deutsch, wie ein guter Reiseführer.
            Behaupte nichts, was nicht in den Fakten steht, außer allgemein Bekanntem. Beziehe den Reisekontext ein (Region, was dort schon geplant ist).
            ${criteriaPrompt(config)}
            Antworte nur mit JSON: $RESEARCH_JSON_SHAPE
        """.trimIndent()
        val user = buildString {
            append(researchPrompt(query))
            if (query.tripContext.isNotBlank()) append("\nReisekontext: ").append(query.tripContext)
            append("\n\nRecherchierte Fakten:\n").append(factLines(all))
        }
        val o = completeJson(model, system, user, GERMAN_PLEASE) { o ->
            val verdict = (o["verdict"] as? JsonPrimitive)?.content.orEmpty()
            val description = (o["description"] as? JsonPrimitive)?.content.orEmpty()
            verdict.isNotBlank() && o["worth"] is JsonPrimitive && !mostlyJapanese(description + " " + verdict)
        }
        return parseResearch(o, config, all.sources)
    }
}
