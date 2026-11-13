package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.guide.GuideMatch
import de.wanderlogplus.shared.guide.GuideTopics
import de.wanderlogplus.shared.guide.GuideWorld
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideLink
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

/**
 * Writes travel guides: [facts] researches the topic (null: from the model's knowledge only), [model] writes the guide,
 * [links] checks the apps and pages it mentions (null: no new link is kept).
 */
class GuideWriter(private val facts: FactSource?, private val model: ChatModel, private val links: LinkCheck? = null) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A complete guide (the id of [existing], else a new one), or [AiFailed]. Pins, hides and the topic of [existing]
     * stay. With [GuideAsk.additions] and an [existing] guide, the checked additions are worked in without new research.
     */
    fun write(ask: GuideAsk, tripContext: String, existing: Guide?, say: (String) -> Unit = {}): Guide {
        if (existing != null && ask.additions.isNotEmpty()) return merge(ask, existing, say)
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
            Behaupte nichts, was nicht in den Fakten oder den geprüften Ergänzungen steht, außer allgemein Bekanntem (Grußformeln, einfache Sätze).
            Sätze zum Zeigen: kurz, höflich, Japanisch in Kana/Kanji, Lautschrift in Romaji.
            $FORMAT_RULES
            Antworte nur mit JSON: {"title": "…", "summary": "Zeile 1\nZeile 2\nZeile 3",
            "sections": [{"heading": "…", "text": "Kern in 1–2 Sätzen", "dos": ["…"], "donts": ["…"], "details": "Hintergrund oder leer"}],
            "phrases": [{"german": "…", "japanese": "…", "romaji": "…", "situation": "wann"}],
            "extraTriggers": [{"kind": "text", "words": ["…"], "on": "PLACE|STAY|ANY"} oder {"kind": "category", "category": "SIGHT|FOOD|SHOPPING|NIGHTLIFE|OTHER"}
              oder {"kind": "stay"} oder {"kind": "train", "words": []} oder {"kind": "flight"}],
            $LINKS_FORMAT}
            extraTriggers nur für Guides ohne festes Thema: wann der Guide in der Reise angezeigt werden soll (Wörter aus Namen oder Kategorien von Orten).
            $LINKS_RULE
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
            if (ask.additions.isNotEmpty()) {
                append("\nGeprüfte Ergänzungen (müssen im Guide stehen):\n")
                ask.additions.forEachIndexed { i, a -> append(i + 1).append(". ").append(a.text).append('\n') }
            }
            found?.let { append("\nRecherchierte Fakten:\n").append(factLines(it)) }
        }
        val o = completeJson(model, system, user, GERMAN_PLEASE) { complete(it) }
        val triggers = (o["extraTriggers"] as? JsonArray).orEmpty().mapNotNull { e ->
            try { json.decodeFromJsonElement(GuideTrigger.serializer(), e) } catch (x: Exception) { null }
        }
        return parsed(o).copy(
            id = existing?.id ?: UUID.randomUUID().toString(),
            topic = topic,
            parentId = existing?.parentId ?: ask.parentId,
            sources = (found?.sources.orEmpty() + ask.additions.flatMap { it.sources }).distinct(),
            extraTriggers = if (topic == null && triggers.isNotEmpty()) triggers else existing?.extraTriggers.orEmpty(),
            pinnedTo = existing?.pinnedTo.orEmpty(),
            hiddenFrom = existing?.hiddenFrom.orEmpty(),
            links = checkedLinks(o, existing, say),
        )
    }

    /** [existing] with the checked [GuideAsk.additions] worked in; fails with "ai_bad_answer" when content would get lost. */
    private fun merge(ask: GuideAsk, existing: Guide, say: (String) -> Unit): Guide {
        say("Guide wird um ${ask.additions.size} geprüfte " + (if (ask.additions.size == 1) "Info" else "Infos") + " ergänzt …")
        val system = """
            Du ergänzt einen bestehenden Guide einer Japan-Reise-App um geprüfte Informationen.
            Behalte alle Abschnitte, Do's, Don'ts und Sätze inhaltlich bei; arbeite die Ergänzungen in passende Abschnitte ein
            oder lege einen neuen Abschnitt an. Knapp, praktisch, auf Deutsch. Gib den ganzen Guide zurück.
            $FORMAT_RULES
            Antworte nur mit JSON: {"title": "…", "summary": "Zeile 1\nZeile 2\nZeile 3",
            "sections": [{"heading": "…", "text": "…", "dos": ["…"], "donts": ["…"], "details": "…"}],
            "phrases": [{"german": "…", "japanese": "…", "romaji": "…", "situation": "…"}],
            $LINKS_FORMAT}
            $LINKS_RULE
        """.trimIndent()
        val user = buildString {
            append("Bestehender Guide:\n")
            append(json.encodeToString(Guide.serializer(), existing.copy(pinnedTo = emptyList(), hiddenFrom = emptyList(), extraTriggers = emptyList(), links = emptyList(), sources = emptyList())))
            append("\n\nGeprüfte Ergänzungen:\n")
            ask.additions.forEachIndexed { i, a -> append(i + 1).append(". ").append(a.text).append('\n') }
            if (ask.wish.isNotBlank()) append("\nWunsch: ").append(ask.wish)
        }
        val o = completeJson(model, system, user, GERMAN_PLEASE) { complete(it) && keepsContent(existing, parsed(it)) }
        val p = parsed(o)
        return existing.copy(
            title = p.title,
            summary = p.summary,
            sections = p.sections,
            phrases = p.phrases,
            sources = (existing.sources + ask.additions.flatMap { it.sources }).distinct(),
            links = checkedLinks(o, existing, say),
        )
    }

    /**
     * [existing] reshaped into glance, core and details without new research: only title, summary, sections and phrases
     * change. Fails with "ai_bad_answer" when the answer loses phrases or sections.
     */
    fun tidy(existing: Guide, say: (String) -> Unit = {}): Guide {
        say("Guide wird gestrafft …")
        val system = """
            Du baust einen Guide einer Japan-Reise-App in ein übersichtliches Format um. Es gibt keine neue Recherche:
            kein Inhalt geht verloren. Was nicht in den Kern eines Abschnitts passt, kommt in dessen details; Doppeltes darfst
            du zusammenfassen. Behalte alle Sätze zum Zeigen. Kürze einen unnötig langen Titel nach der Titel-Regel.
            $FORMAT_RULES
            Antworte nur mit JSON: {"title": "…", "summary": "Zeile 1\nZeile 2\nZeile 3",
            "sections": [{"heading": "…", "text": "…", "dos": ["…"], "donts": ["…"], "details": "…"}],
            "phrases": [{"german": "…", "japanese": "…", "romaji": "…", "situation": "…"}]}
        """.trimIndent()
        val user = "Guide:\n" + json.encodeToString(
            Guide.serializer(),
            existing.copy(pinnedTo = emptyList(), hiddenFrom = emptyList(), extraTriggers = emptyList(), links = emptyList(), sources = emptyList()),
        )
        val o = completeJson(model, system, user, GERMAN_PLEASE) { tidyKeeps(existing, parsed(it)) }
        val p = parsed(o)
        return existing.copy(title = p.title.ifBlank { existing.title }, summary = p.summary, sections = p.sections, phrases = p.phrases)
    }

    /** The apps and pages [g] mentions, checked; empty without a [LinkCheck]. */
    fun extractLinks(g: Guide, say: (String) -> Unit = {}): List<GuideLink> {
        val check = links ?: return emptyList()
        val system = """
            Du liest einen Guide einer Japan-Reise-App und findest die Apps und Webseiten, die darin erwähnt werden.
            Antworte nur mit JSON: {$LINKS_FORMAT}
            $LINKS_RULE Ohne erwähnte Apps oder Webseiten: "links": [].
        """.trimIndent()
        val user = buildString {
            append(g.title).append('\n').append(g.summary).append('\n')
            g.sections.forEach { s ->
                append(s.heading).append(": ").append(s.text).append('\n')
                (s.dos + s.donts).forEach { append("- ").append(it).append('\n') }
            }
        }
        val o = completeJson(model, system, user) { it["links"] is JsonArray }
        val found = parseLinks(o["links"] as? JsonArray)
        if (found.isEmpty()) return emptyList()
        say("${found.size} Links werden geprüft …")
        return check.check(found)
    }

    /** The model's links checked together with the ones [existing] had; without a [LinkCheck] the old ones stay. */
    private fun checkedLinks(o: JsonObject, existing: Guide?, say: (String) -> Unit): List<GuideLink> {
        val check = links ?: return existing?.links.orEmpty()
        val proposed = parseLinks(o["links"] as? JsonArray)
        if (proposed.isEmpty()) return existing?.links.orEmpty()
        say("${proposed.size} Links werden geprüft …")
        return check.check(proposed + existing?.links.orEmpty())
    }

    private fun complete(o: JsonObject) =
        o.text("title").isNotBlank() && o.text("summary").isNotBlank() && (o["sections"] as? JsonArray)?.isNotEmpty() == true && !mostlyJapanese(o.text("summary"))

    /** Title, summary, sections and phrases of a model answer (the id and everything else empty). */
    private fun parsed(o: JsonObject) = Guide(
        id = "",
        title = o.text("title"),
        summary = o.text("summary"),
        sections = (o["sections"] as? JsonArray).orEmpty().mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            GuideSection(s.text("heading").ifBlank { return@mapNotNull null }, s.text("text"), s.strings("dos"), s.strings("donts"), s.text("details"))
        },
        phrases = (o["phrases"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            GuidePhrase(p.text("german"), p.text("japanese"), p.text("romaji"), p.text("situation")).takeIf { it.german.isNotBlank() && it.japanese.isNotBlank() }
        },
    )

    private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.content?.trim().orEmpty()
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }

    companion object {
        /** How a guide title should look: a guideline with examples of what to leave out, never a template. */
        internal val TITLE_RULE = """
            Titel: kurz und treffend, er benennt das Thema. Lass weg, was man aus der Reise ohnehin weiß: Orte, Städte, Strecken,
            „in Japan“, und keine Inhaltsliste als Untertitel. Zum Beispiel „Shinkansen & Suica“ statt
            „Shinkansen & Suica: Tokyo – Nagoya – Shin-Osaka – Hiroshima“ oder „Yakiniku & Grill“ statt „Yakiniku & Grill in Japan“.
            Die Beispiele zeigen nur, was wegfällt; „A & B“ ist kein Muster. Titel haben ganz verschiedene Formen, etwa
            „Trinkgeld“, „Onsen-Etikette“, „Bezahlen mit Karte“ oder „Nachtzüge“. Ein Titel ist so lang, wie das Thema es braucht.
        """.trimIndent()

        /** Shape of a guide: glance, short core per section, background folded away as details. */
        internal val FORMAT_RULES = TITLE_RULE + "\n" + """
            Der Guide dient zum Vorbereiten und zum schnellen Nachschlagen vor Ort.
            summary heißt in der App „Auf einen Blick“: 3 bis 5 Zeilen, durch Zeilenumbrüche getrennt, je eine Kernaussage in höchstens etwa
            80 Zeichen, ohne Aufzählungszeichen, das vor Ort Wichtigste zuerst.
            Höchstens 6 Abschnitte; heading 1 bis 4 Wörter; text ist der Kern in 1 bis 2 Sätzen; höchstens 4 dos und 4 donts,
            je ein kurzer Satz.
            details: was zum Vorbereiten nützt, vor Ort aber nicht gebraucht wird (Hintergrund, Ausnahmen, Beispiele),
            höchstens etwa 600 Zeichen; sonst leer.
            Ergänzungen aus Reels oder Posts: nur die Aussage selbst, nichts über den Beitrag, die Person oder die Geschichte drumherum.
        """.trimIndent()

        private const val LINKS_FORMAT =
            "\"links\": [{\"name\": \"…\", \"kind\": \"app|web\", \"url\": \"https://… oder leer\", \"androidPackage\": \"Android-Paket-ID oder leer\", \"note\": \"wofür, wenige Wörter\"}]"
        private const val LINKS_RULE =
            "links: nur Apps und Webseiten, die im Guide erwähnt werden. androidPackage nur, wenn du die Paket-ID sicher kennst; url ist die offizielle Seite."

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

/** True when [after] has at least as many sections, dos, don'ts and phrases as [before]: nothing got lost in a merge. */
internal fun keepsContent(before: Guide, after: Guide): Boolean =
    after.sections.size >= before.sections.size &&
        after.sections.sumOf { it.dos.size } >= before.sections.sumOf { it.dos.size } &&
        after.sections.sumOf { it.donts.size } >= before.sections.sumOf { it.donts.size } &&
        after.phrases.size >= before.phrases.size

/** True when a tidied [after] still has sections, a summary that is not mostly Japanese, and every phrase of [before]. */
internal fun tidyKeeps(before: Guide, after: Guide): Boolean =
    after.sections.isNotEmpty() && after.summary.isNotBlank() && !mostlyJapanese(after.summary) && after.phrases.size == before.phrases.size
