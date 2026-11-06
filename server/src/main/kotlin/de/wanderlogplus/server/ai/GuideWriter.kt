package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.guide.GuideMatch
import de.wanderlogplus.shared.guide.GuideTopics
import de.wanderlogplus.shared.guide.GuideWorld
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuidePhrase
import de.wanderlogplus.shared.model.GuideSection
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.model.GuideTrigger
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.sync.GuideAsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Writes travel guides: [facts] researches the topic (null: from the model's knowledge only), [model] writes the guide. */
class GuideWriter(private val facts: FactSource?, private val model: ChatModel) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A complete guide (the id of [existing], else a new one), or [AiFailed]. Pins, hides and the topic of [existing] stay. */
    fun write(ask: GuideAsk, tripContext: String, existing: Guide?, say: (String) -> Unit = {}): Guide {
        val topic = ask.topic ?: existing?.topic
        val name = GuideTopics.byKey(topic)?.name ?: ask.title.ifBlank { existing?.title.orEmpty() }
        if (name.isBlank()) throw AiFailed("bad_input")
        val found = facts?.let { f ->
            f.facts(
                FactQuery(
                    objective = "Wie verhält man sich in Japan: $name? Etikette, Ablauf, typische Fehler von Touristen, nützliche japanische Sätze" +
                        (ask.wish.takeIf { it.isNotBlank() }?.let { ". Besonders: $it" } ?: ""),
                    queries = listOf("$name Japan etiquette tourists", "$name Japan how to", "$name Japan Knigge"),
                    hints = listOf("Regeln und Verbote", "Ablauf Schritt für Schritt", "Preise und Bezahlen", "hilfreiche Sätze auf Japanisch"),
                ),
                say,
            ) ?: throw AiFailed("research_empty")
        }
        say("Guide wird geschrieben …")
        val system = """
            Du schreibst für eine Reise-App einen allgemeinen Guide für Reisende in Japan: knapp, praktisch, auf Deutsch, das Wichtigste zuerst.
            Behaupte nichts, was nicht in den Fakten steht, außer allgemein Bekanntem (Grußformeln, einfache Sätze).
            Sätze zum Zeigen: kurz, höflich, Japanisch in Kana/Kanji, Lautschrift in Romaji.
            Antworte nur mit JSON: {"title": "…", "summary": "das Wichtigste in höchstens 3 Zeilen",
            "sections": [{"heading": "…", "text": "kurz", "dos": ["…"], "donts": ["…"]}],
            "phrases": [{"german": "…", "japanese": "…", "romaji": "…", "situation": "wann"}],
            "extraTriggers": [{"kind": "text", "words": ["…"], "on": "PLACE|STAY|ANY"} oder {"kind": "category", "category": "SIGHT|FOOD|SHOPPING|NIGHTLIFE|OTHER"}
              oder {"kind": "stay"} oder {"kind": "train", "words": []} oder {"kind": "flight"}]}
            extraTriggers nur für Guides ohne festes Thema: wann der Guide in der Reise angezeigt werden soll (Wörter aus Namen oder Kategorien von Orten).
        """.trimIndent()
        val user = buildString {
            append("Thema: ").append(name)
            if (topic == null) append(" (freier Guide, schlage extraTriggers vor)")
            if (ask.wish.isNotBlank()) append("\nWunsch: ").append(ask.wish)
            if (tripContext.isNotBlank()) append("\nReisekontext: ").append(tripContext)
            if (existing != null && existing.sections.isNotEmpty()) {
                append("\nBisheriger Guide (überarbeiten, Gutes behalten):\n")
                existing.sections.forEach { s -> append("- ").append(s.heading).append(": ").append(s.text).append('\n') }
            }
            found?.let { append("\nRecherchierte Fakten:\n").append(factLines(it)) }
        }
        val o = completeJson(model, system, user, GERMAN_PLEASE) { o ->
            o.text("title").isNotBlank() && o.text("summary").isNotBlank() && (o["sections"] as? JsonArray)?.isNotEmpty() == true &&
                !mostlyJapanese(o.text("summary"))
        }
        val triggers = (o["extraTriggers"] as? JsonArray).orEmpty().mapNotNull { e ->
            try { json.decodeFromJsonElement(GuideTrigger.serializer(), e) } catch (x: Exception) { null }
        }
        return Guide(
            id = existing?.id ?: UUID.randomUUID().toString(),
            topic = topic,
            parentId = existing?.parentId ?: ask.parentId,
            title = o.text("title"),
            summary = o.text("summary"),
            sections = (o["sections"] as? JsonArray).orEmpty().mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                GuideSection(s.text("heading").ifBlank { return@mapNotNull null }, s.text("text"), s.strings("dos"), s.strings("donts"))
            },
            phrases = (o["phrases"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                GuidePhrase(p.text("german"), p.text("japanese"), p.text("romaji"), p.text("situation")).takeIf { it.german.isNotBlank() && it.japanese.isNotBlank() }
            },
            sources = found?.sources.orEmpty(),
            extraTriggers = if (topic == null && triggers.isNotEmpty()) triggers else existing?.extraTriggers.orEmpty(),
            pinnedTo = existing?.pinnedTo.orEmpty(),
            hiddenFrom = existing?.hiddenFrom.orEmpty(),
        )
    }

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }

    companion object {
        /** Month of the trip, its regions and the trip's places and stays the topic is about (names only, never booking data). */
        fun tripContext(t: ChatTrip, topic: String?): String {
            val parts = mutableListOf<String>()
            t.trip?.let { parts += "Reise ${it.startDate} bis ${it.endDate}" }
            if (t.regions.isNotEmpty()) parts += "Regionen: " + t.regions.joinToString(", ") { it.name }
            if (topic != null) {
                val world = GuideWorld(t.places, t.saved, t.stays, t.trains, t.flights, emptyList(), t.days, t.dayItems)
                val places = t.saved.filter { it.status != PlaceStatus.DROPPED && topic in GuideMatch.topicsFor(GuideTarget(GuideTargetType.PLACE, it.id), world) }
                    .mapNotNull { t.places[it.placeId]?.name?.takeIf { n -> n.isNotBlank() } }
                val stays = t.stays.filter { topic in GuideMatch.topicsFor(GuideTarget(GuideTargetType.STAY, it.id), world) }.map { t.stayName(it) }
                (stays + places).distinct().take(12).takeIf { it.isNotEmpty() }?.let { parts += "betrifft in der Reise: " + it.joinToString(", ") }
            }
            return parts.joinToString("; ")
        }
    }
}
