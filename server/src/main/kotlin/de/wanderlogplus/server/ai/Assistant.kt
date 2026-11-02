package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.Assessment
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.ReelCandidate
import de.wanderlogplus.shared.sync.Worth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** A place the model found in a reel, before geocoding and research. */
data class FoundPlace(val name: String, val localName: String?, val city: String?, val country: String?, val kind: String, val evidence: String)

/** How far from the trip a reel's place may be to count as "on the route", in km (as for regions). */
const val ON_ROUTE_KM = 50.0

private val WORTH_SCALE = """Stufen für "worth": MUST = muss man gesehen haben, WORTH = lohnt sich, OPTIONAL = wenn Zeit ist, SKIP = eher auslassen."""

/**
 * The assistant's work: find places in a reel (frames, sound, caption), locate them, check whether they fit the
 * trip, research them on the web and describe them in German; and judge saved places' worth. Prompts never contain
 * booking data, only place names, regions and reel content.
 */
class Assistant(
    private val model: ChatModel,
    private val geocoder: Geocoder,
    /** Web research per place: Parallel when configured, else the chat model's web search. */
    private val researcher: PlaceResearcher = OpenRouterResearcher(model),
) {

    /** Places named or shown in the reel; empty when there are none. */
    fun findPlaces(caption: String?, media: Media, context: TripContext): Pair<String?, List<FoundPlace>> {
        val system = """
            Du analysierst ein kurzes Reise-Video (Instagram-Reel) für eine Japanreise.
            Finde alle konkreten Orte, die im Video gezeigt, gesagt oder in der Beschreibung erwähnt werden:
            Sehenswürdigkeiten, Restaurants, Cafés, Läden, Bars, Aussichtspunkte, Onsen. Keine ganzen Länder oder Präfekturen.
            Nutze Schilder und Texte in den Bildern, das Gesprochene und die Beschreibung.
            Antworte nur mit JSON: {"summary": "1 Satz auf Deutsch, worum es im Reel geht",
            "locations": [{"name": "üblicher Name in lateinischer Schrift", "localName": "japanischer Name oder null",
            "city": "Stadt", "country": "Land", "kind": "sight|food|shopping|nightlife|other",
            "evidence": "kurz, woran du den Ort erkennst"}]}
            Wenn kein konkreter Ort erkennbar ist: "locations": [].
        """.trimIndent()
        val parts = buildList {
            add(AiPart.Text("Orte der Reise (zur Orientierung): " + context.regionNames.joinToString(", ").ifBlank { "unbekannt" }))
            add(AiPart.Text("Beschreibung des Reels:\n" + (caption?.take(4000) ?: "(keine)")))
            media.frames.forEach { add(AiPart.Image(it)) }
            media.audio?.let { add(AiPart.Audio(it)) }
        }
        val o = OpenRouterModel.jsonObjectIn(model.complete(system, parts, webSearch = false))
        val found = (o["locations"] as? JsonArray).orEmpty().mapNotNull { e ->
            val l = e as? JsonObject ?: return@mapNotNull null
            val name = l.str("name") ?: return@mapNotNull null
            FoundPlace(name, l.str("localName"), l.str("city"), l.str("country"), l.str("kind") ?: "other", l.str("evidence").orEmpty())
        }.distinctBy { (it.name + "|" + it.city).lowercase() }.take(MAX_PLACES)
        return o.str("summary") to found
    }

    /** Coordinates, distance to the trip, web research and a German description for one found place. */
    fun research(p: FoundPlace, context: TripContext): ReelCandidate {
        val hit = listOfNotNull(
            listOfNotNull(p.name, p.city).joinToString(" "),
            p.localName,
            p.name,
        ).distinct().firstNotNullOfOrNull { geocoder.find(it) }
        val near = hit?.let { context.nearest(it.lat, it.lng) }
        val r = try {
            researcher.research(ResearchQuery(p.name, p.localName, p.city, p.country, p.kind, p.evidence))
        } catch (e: AiFailed) {
            null // a place without research is still worth showing
        }
        return ReelCandidate(
            name = p.name,
            city = p.city,
            lat = hit?.lat,
            lng = hit?.lng,
            address = hit?.address,
            category = r?.category?.uppercase()?.takeIf { it in CATEGORIES } ?: categoryOf(p.kind),
            description = r?.description.orEmpty(),
            tips = r?.tips.orEmpty().take(4),
            hints = r?.hints.orEmpty().take(4),
            worth = r?.worth?.let { w -> Worth.entries.firstOrNull { it.name == w.uppercase() } },
            worthReason = r?.worthReason.orEmpty(),
            distanceKm = near?.second?.let { (it * 10).roundToInt() / 10.0 },
            nearestAnchor = near?.first?.name,
            onRoute = near != null && near.second <= ON_ROUTE_KM,
            evidence = p.evidence,
            sources = r?.sources.orEmpty(),
        )
    }

    /** Judges the given saved places (all without judgement when [ids] is empty), in batches. */
    fun assess(store: EntityStore, ids: List<String>): List<Assessment> {
        fun <T> live(type: EntityType, decode: (de.wanderlogplus.shared.sync.EntityRecord) -> T): List<T> =
            store.all(type).filter { !it.isDeleted }.mapNotNull { r -> try { decode(r) } catch (e: Exception) { null } }
        val places = live(EntityType.PLACE) { EntityCodec.decode<Place>(it) }.associateBy { it.id }
        val regions = live(EntityType.REGION) { EntityCodec.decode<Region>(it) }.associateBy { it.id }
        val saved = live(EntityType.SAVED_PLACE) { EntityCodec.decode<SavedPlace>(it) }
        val wanted = if (ids.isEmpty()) saved.filter { it.worth == null } else saved.filter { it.id in ids }
        val system = """
            Du bewertest Orte einer Japanreise danach, wie lohnenswert ein Besuch ist, für Reisende mit begrenzter Zeit.
            Berücksichtige Bekanntheit, Bewertungen, Einzigartigkeit und Aufwand.
            $WORTH_SCALE
            Antworte nur mit JSON: {"results": [{"id": "die gegebene id", "worth": "MUST|WORTH|OPTIONAL|SKIP", "reason": "1 kurzer Satz auf Deutsch"}]}
        """.trimIndent()
        return wanted.chunked(ASSESS_BATCH).flatMap { batch ->
            val lines = batch.joinToString("\n") { s ->
                val p = places[s.placeId]
                listOfNotNull(
                    "id=${s.id}",
                    "name=${p?.name ?: "?"}",
                    s.regionId?.let { regions[it]?.name }?.let { "region=$it" },
                    "kategorie=${s.category.name}",
                    p?.rating?.let { "bewertung=$it (${p.numRatings ?: 0})" },
                    p?.description?.take(300)?.let { "info=$it" },
                ).joinToString("; ")
            }
            val o = OpenRouterModel.jsonObjectIn(model.complete(system, listOf(AiPart.Text(lines)), webSearch = false))
            val known = batch.map { it.id }.toSet()
            (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val id = r.str("id")?.takeIf { it in known } ?: return@mapNotNull null
                val worth = r.str("worth")?.let { w -> Worth.entries.firstOrNull { it.name == w.uppercase() } } ?: return@mapNotNull null
                Assessment(id, worth, r.str("reason").orEmpty())
            }
        }
    }

    private fun JsonObject?.strings(key: String): List<String> =
        (this?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }

    companion object {
        const val MAX_PLACES = 8
        const val ASSESS_BATCH = 25
        private val CATEGORIES = setOf("SIGHT", "FOOD", "SHOPPING", "NIGHTLIFE", "OTHER")

        fun categoryOf(kind: String) = when (kind.lowercase()) {
            "sight" -> "SIGHT"
            "food" -> "FOOD"
            "shopping" -> "SHOPPING"
            "nightlife" -> "NIGHTLIFE"
            else -> "OTHER"
        }
    }
}

/** Research with the chat model's web search plugin (OpenRouter), used when no Parallel key is configured. */
class OpenRouterResearcher(private val model: ChatModel) : PlaceResearcher {
    override fun research(query: ResearchQuery): PlaceResearch? {
        val system = """
            Du schreibst für eine Reise-App kurze, sachliche Ortsinfos auf Deutsch, im Stil eines Reiseführers.
            Recherchiere im Web Bewertungen, Tipps und praktische Hinweise (Öffnungszeiten, Reservierung, Andrang, Preise, beste Zeit).
            $WORTH_SCALE
            Antworte nur mit JSON: {"description": "2–4 Sätze, was der Ort ist und was ihn ausmacht",
            "tips": ["höchstens 4 kurze Tipps"], "hints": ["höchstens 4 Hinweise/Warnungen"],
            "worth": "MUST|WORTH|OPTIONAL|SKIP", "worthReason": "1 Satz Begründung",
            "category": "SIGHT|FOOD|SHOPPING|NIGHTLIFE|OTHER"}
        """.trimIndent()
        val prompt = buildString {
            append("Ort: ").append(query.name)
            query.localName?.let { append(" (").append(it).append(")") }
            query.city?.let { append(", ").append(it) }
            query.country?.let { append(", ").append(it) }
            append("\nArt laut Video: ").append(query.kind)
            if (query.evidence.isNotBlank()) append("\nIm Reel: ").append(query.evidence)
        }
        val o = OpenRouterModel.jsonObjectIn(model.complete(system, listOf(AiPart.Text(prompt)), webSearch = true))
        fun strings(key: String) = (o[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
        return PlaceResearch(
            description = o.str("description").orEmpty(),
            tips = strings("tips"),
            hints = strings("hints"),
            worth = o.str("worth"),
            worthReason = o.str("worthReason").orEmpty(),
            category = o.str("category"),
            sources = emptyList(),
        )
    }
}
