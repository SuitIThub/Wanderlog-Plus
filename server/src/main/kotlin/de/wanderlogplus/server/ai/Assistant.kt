package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.model.CriterionScore
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PlacePoi
import de.wanderlogplus.shared.model.PoiKinds
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.Assessment
import de.wanderlogplus.shared.sync.DossierResult
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.ReelCandidate
import de.wanderlogplus.shared.sync.Worth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** A place the model found in a reel, before geocoding and research. */
data class FoundPlace(val name: String, val localName: String?, val city: String?, val country: String?, val kind: String, val evidence: String)

/** How far from the trip a reel's place may be to count as "on the route", in km (as for regions). */
const val ON_ROUTE_KM = 50.0

/** How many places are researched at the same time. */
const val RESEARCH_PARALLELISM = 4

internal val WORTH_SCALE = """Gesamtfazit "worth": MUST = muss man gesehen haben, WORTH = lohnt sich, OPTIONAL = wenn Zeit ist, SKIP = eher auslassen."""

/** The criteria part of a prompt: what to score (1–5, higher = better for the travellers) and the user's own wishes. */
internal fun criteriaPrompt(config: AssessConfig): String = buildString {
    val chosen = config.criteria.mapNotNull { Criteria.byKey(it) }.ifEmpty { Criteria.DEFAULT.mapNotNull { Criteria.byKey(it) } }
    append("Bewerte jedes dieser Kriterien mit 1–5 Punkten (5 = am besten für die Reisenden) und einer kurzen Notiz:\n")
    chosen.forEach { append("- ").append(it.key).append(" (").append(it.label).append("): ").append(it.question).append('\n') }
    append("Leite daraus ein Gesamtfazit ab (verdict: 1–2 Sätze) und die Stufe worth. ").append(WORTH_SCALE).append('\n')
    append("Jede Region der Reise wird ohnehin besucht: werte nie die Anreise in die Region ab, nur einen Abstecher innerhalb der Region.\n")
    config.extraPrompt.trim().takeIf { it.isNotEmpty() }?.let { append("Wünsche der Reisenden: ").append(it.take(1000)).append('\n') }
}

/** Criteria scores from a model answer, limited to the chosen keys, labels filled in, scores clamped to 1–5. */
internal fun parseCriteria(arr: JsonArray?, config: AssessConfig): List<CriterionScore> {
    val allowed = config.criteria.ifEmpty { Criteria.DEFAULT }.toSet()
    return arr.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val key = o.str("key")?.lowercase()?.takeIf { it in allowed } ?: return@mapNotNull null
        val score = (o["score"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.roundToInt() }?.coerceIn(1, 5) ?: return@mapNotNull null
        CriterionScore(key, Criteria.byKey(key)?.label ?: key, score, o.str("note").orEmpty())
    }.distinctBy { it.key }
}

internal fun parseWorth(s: String?): Worth? = s?.let { w -> Worth.entries.firstOrNull { it.name == w.uppercase() } }

/**
 * The assistant's work: find places in a reel (frames, sound, caption), locate them, check whether they fit the
 * trip, research them on the web (insider tips, hidden spots, good-to-know, criteria scores and verdict, in German);
 * research saved places the same way; and judge saved places cheaply by criteria without web search.
 * Prompts never contain booking data, only place names, regions, categories and reel content.
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

    /** Coordinates, distance to the trip and the full research for one place found in a reel. */
    fun research(p: FoundPlace, context: TripContext, config: AssessConfig = AssessConfig(), say: (String) -> Unit = {}): ReelCandidate {
        say("Lage wird gesucht (Photon) …")
        val (hit, issue) = locate(p, say)
        val near = hit?.let { context.nearest(it.lat, it.lng) }
        if (hit != null) {
            say("Lage: " + (hit.address ?: "gefunden") + near?.let { (a, km) -> " · " + String.format(java.util.Locale.GERMAN, "%.1f", km) + " km bis " + a.name }.orEmpty())
        }
        val r = try {
            researcher.research(ResearchQuery(p.name, p.localName, p.city, p.country, p.kind, p.evidence), config, say)
        } catch (e: AiFailed) {
            say("Recherche fehlgeschlagen (${e.code})")
            null // a place without research is still worth showing
        }
        val d = r?.dossier?.let { it.copy(pois = locatePois(it.pois, hit?.lat, hit?.lng, p.name, p.city, geocoder)) }
        if (r != null) say(poiLine(r.dossier.pois.size, d?.pois?.size ?: 0))
        return ReelCandidate(
            name = p.name,
            city = p.city,
            lat = hit?.lat,
            lng = hit?.lng,
            address = hit?.address,
            category = r?.category?.uppercase()?.takeIf { it in CATEGORIES } ?: categoryOf(p.kind),
            description = r?.description.orEmpty(),
            tips = d?.tips.orEmpty(),
            hints = d?.hints.orEmpty(),
            worth = parseWorth(r?.worth),
            worthReason = d?.verdict.orEmpty(),
            distanceKm = near?.second?.let { (it * 10).roundToInt() / 10.0 },
            nearestAnchor = near?.first?.name,
            onRoute = near != null && near.second <= ON_ROUTE_KM,
            evidence = p.evidence,
            sources = d?.sources.orEmpty(),
            dossier = d,
            positionIssue = issue,
        )
    }

    /**
     * Coordinates of a reel place: the full name with city, the local name, the bare name; then ever shorter names
     * ("Sunshine 60 Observatory TENBOU PARK" -> "Sunshine 60") with the city, accepted only within [SHORT_NAME_MAX_KM]
     * of the city. Returns the hit or why there is none ("not_found", "unavailable").
     */
    internal fun locate(p: FoundPlace, say: (String) -> Unit = {}): Pair<GeoHit?, String?> = try {
        val full = listOfNotNull(listOfNotNull(p.name, p.city).joinToString(" "), p.localName, p.name).distinct()
        full.firstNotNullOfOrNull { geocoder.find(it) }?.let { return it to null }
        val words = p.name.trim().split(Regex("\\s+"))
        val city = p.city?.takeIf { it.isNotBlank() && words.size > 2 }?.let { geocoder.find(it) }
        val shortHit = city?.let { c ->
            (words.size - 1 downTo 2).asSequence()
                .map { k -> words.take(k).joinToString(" ") }
                .filter { it.length >= 4 }
                .firstNotNullOfOrNull { short ->
                    geocoder.find("$short ${p.city}")
                        ?.takeIf { distanceKm(c.lat, c.lng, it.lat, it.lng) <= SHORT_NAME_MAX_KM }
                        ?.also { say("Lage über den kürzeren Namen „$short“ gefunden") }
                }
        }
        if (shortHit != null) shortHit to null else { say("Lage nicht gefunden"); null to "not_found" }
    } catch (e: GeocoderUnavailable) {
        say("Ortsdienst (Photon) antwortet nicht, Lage fehlt")
        null to "unavailable"
    }

    /** Researches several reel places at once (at most [RESEARCH_PARALLELISM] at a time), keeping their order. */
    fun researchAll(
        found: List<FoundPlace>,
        context: TripContext,
        config: AssessConfig,
        progress: JobProgress = JobProgress.NONE,
        onDone: (Int) -> Unit = {},
    ): List<ReelCandidate> {
        val keyed = found.mapIndexed { i, p -> "place-$i" to p }
        progress.items(keyed.map { (k, p) -> JobItem(k, listOfNotNull(p.name, p.city).joinToString(", ")) })
        return inParallel(keyed, onDone) { (key, p) ->
            progress.item(key, state = "RUNNING")
            val c = research(p, context, config, progress.sayFor(key, p.name))
            if (c.dossier == null) {
                progress.item(key, state = "FAILED", detail = "ohne Recherche, nur Lage")
                progress.step("${p.name}: ohne Recherche übernommen", JobProgress.WARN)
            } else {
                progress.item(key, state = "DONE", detail = worthText(c.worth))
                progress.step("${p.name}: fertig, ${worthText(c.worth)}", JobProgress.OK)
            }
            c
        }
    }

    /** Web research for saved places of the trip: full dossier, worth and (for places without one) a description. */
    fun researchSaved(
        store: EntityStore,
        ids: List<String>,
        config: AssessConfig,
        now: () -> Long,
        progress: JobProgress = JobProgress.NONE,
        /** Called on the research thread as soon as one place has its result. */
        onResult: (DossierResult) -> Unit = {},
        onDone: (Int) -> Unit = {},
    ): List<DossierResult> {
        val data = TripData.from(store)
        val wanted = data.saved.filter { it.id in ids }
        if (wanted.size < ids.size) progress.step("${ids.size - wanted.size} Orte nicht gefunden (gelöscht?)", JobProgress.WARN)
        val nameOf = wanted.associate { it.id to (data.places[it.placeId]?.name ?: "?") }
        progress.items(wanted.map { s -> JobItem(s.id, nameOf.getValue(s.id) + (s.regionId?.let { data.regions[it]?.name }?.let { ", $it" }.orEmpty())) })
        return inParallel(wanted, onDone) { s ->
            val p = data.places[s.placeId]
            val name = nameOf.getValue(s.id)
            val say = progress.sayFor(s.id, name)
            progress.item(s.id, state = "RUNNING")
            val region = s.regionId?.let { data.regions[it]?.name }
            val query = ResearchQuery(p?.name ?: "?", null, region, "Japan", s.category.name.lowercase(), p?.description?.take(300).orEmpty())
            val r = try {
                researcher.research(query, config, say)
            } catch (e: AiFailed) {
                say("Recherche fehlgeschlagen (${e.code})")
                null
            }
            val result = r?.let {
                val pois = locatePois(it.dossier.pois, p?.lat, p?.lng, p?.name.orEmpty(), region, geocoder)
                say(poiLine(it.dossier.pois.size, pois.size))
                DossierResult(s.id, parseWorth(it.worth), it.description, it.dossier.copy(researchedAt = now(), pois = pois))
            }
            result?.let(onResult)
            if (result == null) {
                progress.item(s.id, state = "FAILED", detail = "kein Ergebnis")
                progress.step("$name: kein Ergebnis", JobProgress.WARN)
            } else {
                progress.item(s.id, state = "DONE", detail = worthText(result.worth))
                progress.step("$name: fertig, ${worthText(result.worth)}", JobProgress.OK)
            }
            result
        }.filterNotNull()
    }

    /** Judges the given saved places by the chosen criteria (all without judgement when [ids] is empty), in batches; no web search. */
    fun assess(
        store: EntityStore,
        ids: List<String>,
        config: AssessConfig = AssessConfig(),
        progress: JobProgress = JobProgress.NONE,
        /** Called after every batch with its judgements. */
        onBatch: (List<Assessment>) -> Unit = {},
    ): List<Assessment> {
        val data = TripData.from(store)
        val wanted = if (ids.isEmpty()) data.saved.filter { it.worth == null } else data.saved.filter { it.id in ids }
        val batches = (wanted.size + ASSESS_BATCH - 1) / ASSESS_BATCH
        progress.step(if (wanted.isEmpty()) "Keine Orte ohne Einschätzung" else "${wanted.size} Orte, $batches Durchgänge zu höchstens $ASSESS_BATCH")
        val system = """
            Du bewertest Orte einer Japanreise danach, wie lohnenswert ein Besuch ist, für Reisende mit begrenzter Zeit.
            ${criteriaPrompt(config)}
            Antworte nur mit JSON: {"results": [{"id": "die gegebene id", "criteria": [{"key": "…", "score": 1-5, "note": "kurz, Deutsch"}],
            "worth": "MUST|WORTH|OPTIONAL|SKIP", "verdict": "Gesamtfazit, 1–2 Sätze auf Deutsch"}]}
        """.trimIndent()
        return wanted.chunked(ASSESS_BATCH).withIndex().flatMap { (bi, batch) ->
            progress.step("Durchgang ${bi + 1}/$batches: ${batch.size} Orte werden eingeschätzt …")
            val lines = batch.joinToString("\n") { s ->
                val p = data.places[s.placeId]
                listOfNotNull(
                    "id=${s.id}",
                    "name=${p?.name ?: "?"}",
                    s.regionId?.let { data.regions[it]?.name }?.let { "region=$it" },
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
                val worth = parseWorth(r.str("worth")) ?: return@mapNotNull null
                Assessment(id, worth, r.str("verdict") ?: r.str("reason").orEmpty(), parseCriteria(r["criteria"] as? JsonArray, config))
            }.also(onBatch)
        }
    }

    private fun <T, R> inParallel(items: List<T>, onDone: (Int) -> Unit, work: (T) -> R): List<R> {
        if (items.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(RESEARCH_PARALLELISM, items.size))
        val done = AtomicInteger()
        try {
            return pool.invokeAll(items.map { item -> Callable { work(item).also { onDone(done.incrementAndGet()) } } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        const val MAX_PLACES = 8

        /** A hit for a shortened name must lie this close to the named city. */
        const val SHORT_NAME_MAX_KM = 25.0
        const val ASSESS_BATCH = 25
        internal val CATEGORIES = setOf("SIGHT", "FOOD", "SHOPPING", "NIGHTLIFE", "OTHER")

        fun categoryOf(kind: String) = when (kind.lowercase()) {
            "sight" -> "SIGHT"
            "food" -> "FOOD"
            "shopping" -> "SHOPPING"
            "nightlife" -> "NIGHTLIFE"
            else -> "OTHER"
        }
    }
}

/** The trip's places, regions and saved places from the server's store (deleted records skipped). */
internal class TripData(val places: Map<String, Place>, val regions: Map<String, Region>, val saved: List<SavedPlace>) {
    companion object {
        fun from(store: EntityStore): TripData {
            fun <T> live(type: EntityType, decode: (EntityRecord) -> T): List<T> =
                store.all(type).filter { !it.isDeleted }.mapNotNull { r -> try { decode(r) } catch (e: Exception) { null } }
            return TripData(
                live(EntityType.PLACE) { EntityCodec.decode<Place>(it) }.associateBy { it.id },
                live(EntityType.REGION) { EntityCodec.decode<Region>(it) }.associateBy { it.id },
                live(EntityType.SAVED_PLACE) { EntityCodec.decode<SavedPlace>(it) },
            )
        }
    }
}

/** Research with the chat model's web search plugin (OpenRouter), used when no Parallel key is configured. */
class OpenRouterResearcher(private val model: ChatModel) : PlaceResearcher {
    override fun research(query: ResearchQuery, config: AssessConfig, say: (String) -> Unit): ResearchOutcome? {
        say("Recherche mit Websuche (OpenRouter) …")
        val system = """
            Du recherchierst für eine Reise-App im Web zu einem Ort und schreibst sachlich auf Deutsch, wie ein guter Reiseführer.
            Suche Bewertungen, praktische Hinweise, Geheimtipps von Einheimischen und Kennern, versteckte Ecken in der Nähe und Dinge, die man vorher wissen sollte.
            ${criteriaPrompt(config)}
            Antworte nur mit JSON: ${RESEARCH_JSON_SHAPE}
        """.trimIndent()
        return parseResearch(OpenRouterModel.jsonObjectIn(model.complete(system, listOf(AiPart.Text(researchPrompt(query))), webSearch = true)), config, emptyList())
    }
}

/** The JSON shape both researchers ask for. */
internal const val RESEARCH_JSON_SHAPE = """{"description": "2–4 Sätze, was der Ort ist und was ihn ausmacht",
"category": "SIGHT|FOOD|SHOPPING|NIGHTLIFE|OTHER",
"criteria": [{"key": "Kriterium", "score": 1-5, "note": "kurz"}], "worth": "MUST|WORTH|OPTIONAL|SKIP", "verdict": "Gesamtfazit, 1–2 Sätze",
"insiderTips": ["Geheimtipps, höchstens 4"], "hiddenSpots": ["versteckte Ecken oder kleine Orte in der Nähe, höchstens 4"],
"goodToKnow": ["Gut zu wissen, höchstens 4"], "tips": ["praktische Tipps, höchstens 4"], "hints": ["Warnungen/Hinweise, höchstens 4"],
"nearbyPoints": [{"name": "konkreter Name, z. B. Nordeingang, Aussichtsplattform, nächster Bahnhof", "kind": "entrance|viewpoint|hidden|station|food|parking|other", "note": "kurz, Deutsch", "lat": 0, "lng": 0}]}
nearbyPoints: höchstens 6 konkrete Punkte, die zum Ort gehören oder direkt daneben liegen (weitere Eingänge/Tore, Aussichtspunkte, die versteckten Ecken, nächster Bahnhof). lat/lng nur, wenn du sie sicher kennst, sonst 0."""

internal fun researchPrompt(q: ResearchQuery) = buildString {
    append("Ort: ").append(q.name)
    q.localName?.let { append(" (").append(it).append(")") }
    q.city?.let { append(", ").append(it) }
    q.country?.let { append(", ").append(it) }
    append("\nArt: ").append(q.kind)
    if (q.evidence.isNotBlank()) append("\nBekannt: ").append(q.evidence)
}

/** A research answer (from either researcher) as outcome; [extraSources] are pages found before (search). */
internal fun parseResearch(o: JsonObject, config: AssessConfig, extraSources: List<String>): ResearchOutcome {
    fun strings(key: String) = (o[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.take(4)
    return ResearchOutcome(
        description = o.str("description").orEmpty(),
        category = o.str("category"),
        worth = o.str("worth"),
        dossier = PlaceDossier(
            criteria = parseCriteria(o["criteria"] as? JsonArray, config),
            verdict = o.str("verdict") ?: o.str("worthReason").orEmpty(),
            insiderTips = strings("insiderTips"),
            hiddenSpots = strings("hiddenSpots"),
            goodToKnow = strings("goodToKnow"),
            tips = strings("tips"),
            hints = strings("hints"),
            sources = extraSources.distinct().take(ParallelResearcher.MAX_SOURCES),
            pois = parsePois(o["nearbyPoints"] as? JsonArray),
        ),
    )
}

/** Raw related spots from a research answer (coordinates unchecked; 0 = unknown). */
internal fun parsePois(arr: JsonArray?): List<PlacePoi> = arr.orEmpty().mapNotNull { e ->
    val p = e as? JsonObject ?: return@mapNotNull null
    val name = p.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    fun num(k: String) = (p[k] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
    PlacePoi(name.take(120), PoiKinds.normalize(p.str("kind")), num("lat"), num("lng"), p.str("note").orEmpty().take(300))
}.distinctBy { it.name.lowercase() }.take(MAX_POIS)

/** At most this many related spots per place. */
internal const val MAX_POIS = 6

/** Related spots farther than this from the place are dropped (wrong coordinates or a namesake elsewhere). */
internal const val MAX_POI_KM = 3.0

/**
 * Keeps only spots with believable coordinates near ([lat], [lng]): the model's own when within [MAX_POI_KM],
 * else the geocoder's hit for the spot's name (with place and [city]) when within range; the rest is dropped.
 * Without an anchor nothing is kept.
 */
internal fun locatePois(pois: List<PlacePoi>, lat: Double?, lng: Double?, placeName: String, city: String?, geocoder: Geocoder): List<PlacePoi> {
    if (lat == null || lng == null || (lat == 0.0 && lng == 0.0)) return emptyList()
    fun near(a: Double, b: Double) = !(a == 0.0 && b == 0.0) && distanceKm(lat, lng, a, b) <= MAX_POI_KM
    return pois.mapNotNull { p ->
        if (near(p.lat, p.lng)) return@mapNotNull p
        val hit = listOf(
            listOfNotNull(p.name, placeName.ifBlank { null }, city).joinToString(" "),
            listOfNotNull(p.name, city).joinToString(" "),
        ).distinct().firstNotNullOfOrNull { q ->
            val h = try { geocoder.find(q) } catch (e: Exception) { null }
            h?.takeIf { near(it.lat, it.lng) }
        }
        hit?.let { p.copy(lat = it.lat, lng = it.lng) }
    }
}

/** Log line about related spots: how many came back and how many have a checked position. */
internal fun poiLine(found: Int, kept: Int): String = when {
    found == 0 -> "Keine Punkte vor Ort gefunden"
    else -> "Punkte vor Ort: $kept von $found mit gesicherter Lage"
}
