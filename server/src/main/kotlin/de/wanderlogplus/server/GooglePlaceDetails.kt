package de.wanderlogplus.server

import de.wanderlogplus.server.ai.Fact
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Fetches one place's details from the Places API (New); returns status and body. Replaced in tests. */
fun interface PlaceDetailsTransport {
    fun details(googlePlaceId: String): Pair<Int, String>
}

class HttpPlaceDetailsTransport(private val apiKey: String) : PlaceDetailsTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun details(googlePlaceId: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/places/" + URLEncoder.encode(googlePlaceId, "UTF-8") + "?languageCode=de"))
            .timeout(Duration.ofSeconds(20))
            .header("X-Goog-Api-Key", apiKey)
            .header(
                "X-Goog-FieldMask",
                "id,regularOpeningHours,rating,userRatingCount,priceLevel,websiteUri,internationalPhoneNumber,googleMapsUri,primaryTypeDisplayName,editorialSummary,businessStatus",
            )
            .GET().build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        } catch (e: IOException) {
            500 to ""
        }
    }
}

/** What Google knows about a place that the trip uses: hours, rating, price, contact, type. */
@Serializable
data class GoogleDetails(
    val openingPeriods: List<OpeningPeriod> = emptyList(),
    val rating: Double? = null,
    val numRatings: Int? = null,
    /** 0 (free) to 4 (very expensive). */
    val priceLevel: Int? = null,
    val website: String? = null,
    val phone: String? = null,
    val googleMapsUrl: String? = null,
    val type: String? = null,
    val summary: String? = null,
    val closedPermanently: Boolean = false,
) {
    /** German facts for the research, each backed by the Google Maps page. */
    fun facts(): List<Fact> {
        val src = listOfNotNull(googleMapsUrl)
        return buildList {
            if (closedPermanently) add("Laut Google dauerhaft geschlossen.")
            if (openingPeriods.isNotEmpty()) add("Öffnungszeiten laut Google: " + openingPeriods.joinToString(", ") { "${DAYS[it.openDay % 7]} ${it.openTime}–${it.closeTime ?: "?"}" })
            rating?.let { r -> add("Bewertung bei Google: " + String.format(Locale.GERMAN, "%.1f", r) + " von 5" + (numRatings?.let { " ($it Bewertungen)" } ?: "")) }
            priceLevel?.let { add("Preisniveau laut Google: " + PRICES.getOrElse(it) { "unbekannt" }) }
            type?.let { add("Art laut Google: $it") }
            summary?.let { add("Kurzbeschreibung bei Google: $it") }
            website?.let { add("Website: $it") }
        }.map { Fact(it, src) }
    }

    companion object {
        private val DAYS = listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")
        private val PRICES = listOf("kostenlos", "günstig", "mittel", "gehoben", "sehr teuer")
    }
}

/** Google details of places, cached for [TTL] (also "Google does not know it"), so each costs quota once. */
class GooglePlaceDetails(
    private val store: SqliteStore,
    private val transport: PlaceDetailsTransport,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The details, or null when Google does not know the id or cannot be reached. */
    fun get(googlePlaceId: String): GoogleDetails? {
        val key = "gdetails:$googlePlaceId"
        store.cachedRoute(key)?.let { (text, at) ->
            if (now().toEpochMilli() - at < TTL.toMillis()) return if (text == "{}") null else json.decodeFromString(GoogleDetails.serializer(), text)
        }
        val (status, body) = transport.details(googlePlaceId)
        if (status in 500..599 || status == 429) return null // try again another time
        val details = if (status in 200..299) parse(body) else null
        store.putCachedRoute(key, details?.let { json.encodeToString(GoogleDetails.serializer(), it) } ?: "{}", now().toEpochMilli())
        return details
    }

    internal fun parse(body: String): GoogleDetails? {
        val o = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) { return null }
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun text(k: String) = ((o[k] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        fun time(p: JsonObject?): Pair<Int, String>? {
            p ?: return null
            val day = (p["day"] as? JsonPrimitive)?.intOrNull ?: return null
            val h = (p["hour"] as? JsonPrimitive)?.intOrNull ?: 0
            val m = (p["minute"] as? JsonPrimitive)?.intOrNull ?: 0
            return day to "%02d:%02d".format(h, m)
        }
        val periods = ((o["regularOpeningHours"] as? JsonObject)?.get("periods") as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val (openDay, openTime) = time(p["open"] as? JsonObject) ?: return@mapNotNull null
            val close = time(p["close"] as? JsonObject)
            OpeningPeriod(openDay, openTime, close?.first, close?.second)
        }
        return GoogleDetails(
            openingPeriods = periods,
            rating = (o["rating"] as? JsonPrimitive)?.doubleOrNull,
            numRatings = (o["userRatingCount"] as? JsonPrimitive)?.intOrNull,
            priceLevel = when (str("priceLevel")) {
                "PRICE_LEVEL_FREE" -> 0
                "PRICE_LEVEL_INEXPENSIVE" -> 1
                "PRICE_LEVEL_MODERATE" -> 2
                "PRICE_LEVEL_EXPENSIVE" -> 3
                "PRICE_LEVEL_VERY_EXPENSIVE" -> 4
                else -> null
            },
            website = str("websiteUri"),
            phone = str("internationalPhoneNumber"),
            googleMapsUrl = str("googleMapsUri"),
            type = text("primaryTypeDisplayName"),
            summary = text("editorialSummary"),
            closedPermanently = str("businessStatus") == "CLOSED_PERMANENTLY",
        )
    }

    companion object {
        val TTL: Duration = Duration.ofDays(30)
    }
}

/**
 * Fills the empty fields of places that have a Google id but no hours and no rating (taken over from a reel or the
 * chat) with Google's details, once per place, as the server. Fields the travellers filled stay.
 */
class PlaceEnricher(
    private val store: SqliteStore,
    private val sync: SyncService,
    private val details: GooglePlaceDetails,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { encodeDefaults = true }
    private val log = LoggerFactory.getLogger(PlaceEnricher::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "place-enricher").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)

    /** Runs [enrichAll] in the background; calls while one is waiting are merged. */
    fun schedule() {
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            try { enrichAll() } catch (e: Exception) { log.warn("Place enrichment failed: {}", e.javaClass.simpleName) }
        }
    }

    /** Enriches every place that needs it; returns how many were written. */
    fun enrichAll(): Int {
        val places = store.all(EntityType.PLACE).filter { !it.isDeleted }.mapNotNull { r -> try { EntityCodec.decode<Place>(r) } catch (e: Exception) { null } }
        var written = 0
        places.filter { it.googlePlaceId.isNotBlank() && it.openingPeriods.isEmpty() && it.rating == null }.forEach { p ->
            val mark = "genrich:${p.googlePlaceId}"
            if (store.cachedRoute(mark) != null) return@forEach
            val d = details.get(p.googlePlaceId) ?: run { store.putCachedRoute(mark, "{}", now().toEpochMilli()); return@forEach }
            val fields = buildMap<String, JsonElement> {
                if (d.openingPeriods.isNotEmpty()) put("openingPeriods", json.encodeToJsonElement(ListSerializer(OpeningPeriod.serializer()), d.openingPeriods))
                d.rating?.let { put("rating", JsonPrimitive(it)) }
                if (p.numRatings == null) d.numRatings?.let { put("numRatings", JsonPrimitive(it)) }
                if (p.priceLevel == null) d.priceLevel?.let { put("priceLevel", JsonPrimitive(it)) }
                if (p.website.isNullOrBlank()) d.website?.let { put("website", JsonPrimitive(it)) }
                if (p.phone.isNullOrBlank()) d.phone?.let { put("phone", JsonPrimitive(it)) }
                if (p.googleMapsUrl.isNullOrBlank()) d.googleMapsUrl?.let { put("googleMapsUrl", JsonPrimitive(it)) }
                if (p.categories.isEmpty()) d.type?.let { put("categories", JsonArray(listOf(JsonPrimitive(it)))) }
            }
            if (fields.isNotEmpty() && sync.writeAsServer(ASSISTANT_AUTHOR, listOf(ServerEdit(EntityType.PLACE, p.id, fields))) > 0) written++
            store.putCachedRoute(mark, "{}", now().toEpochMilli())
        }
        return written
    }
}
