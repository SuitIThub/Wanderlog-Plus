package de.wanderlogplus.server.tabelog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One restaurant as Tabelog lists it. */
data class TabelogHit(
    val id: String,
    val name: String,
    val url: String,
    val score: Double?,
    val reviewCount: Int?,
    val genre: String,
    val lat: Double?,
    val lng: Double?,
    val budgetLunch: String,
    val budgetDinner: String,
    val hours: String,
    val reservation: String,
    val station: String,
)

/** One review (Japanese) with its title and visit/post date. */
data class TabelogReview(val title: String, val text: String, val date: String)

/** Apify could not be asked (network, credit, actor error); [code] is fixed text like "apify_402". */
class TabelogUnavailable(val code: String) : RuntimeException(code)

/** Tabelog lookups; replaced by fakes in tests. Blocking; throws [TabelogUnavailable]. */
interface TabelogSource {
    /** Up to three restaurants for a Japanese [keyword] in prefecture slug [area] ("all" for everywhere), without reviews. */
    fun search(keyword: String, area: String): List<TabelogHit>

    /** The restaurant at [url] and its three newest reviews. */
    fun details(url: String): Pair<TabelogHit?, List<TabelogReview>>
}

/**
 * Tabelog through an Apify actor (default `huggable_quote~tabelog-scraper`): the actor bills each result row and gives
 * one row per review, so a search runs without reviews and only the matched restaurant gets three.
 */
class ApifyTabelogSource(
    private val token: String,
    private val actor: String = DEFAULT_ACTOR,
    /** Sends the input JSON, returns status and body; HTTP by default. */
    transport: ((String) -> Pair<Int, String>)? = null,
) : TabelogSource {
    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build() }
    private val send: (String) -> Pair<Int, String> = transport ?: ::post

    override fun search(keyword: String, area: String): List<TabelogHit> =
        run(
            buildJsonObject {
                put("searchKeyword", keyword)
                put("searchArea", area)
                put("maxRestaurants", 3)
                put("includeReviews", false)
            },
        ).first

    override fun details(url: String): Pair<TabelogHit?, List<TabelogReview>> {
        val (hits, reviews) = run(
            buildJsonObject {
                putJsonArray("startUrls") { add(buildJsonObject { put("url", url) }) }
                put("includeReviews", true)
                put("maxReviewsPerRestaurant", 3)
                put("sortBy", "newest")
            },
        )
        val hit = hits.firstOrNull()
        return hit to (hit?.let { reviews[it.id] }.orEmpty())
    }

    private fun run(input: JsonObject): Pair<List<TabelogHit>, Map<String, List<TabelogReview>>> {
        val (status, body) = send(input.toString())
        if (status !in 200..299) throw TabelogUnavailable("apify_$status")
        return try {
            parseTabelogRows(body)
        } catch (e: Exception) {
            throw TabelogUnavailable("apify_bad_answer")
        }
    }

    private fun post(body: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("https://api.apify.com/v2/acts/$actor/run-sync-get-dataset-items?timeout=240"))
            .timeout(Duration.ofSeconds(270))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()
        return try {
            val r = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            r.statusCode() to r.body()
        } catch (e: IOException) {
            throw TabelogUnavailable("apify_unreachable")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TabelogUnavailable("apify_unreachable")
        }
    }

    companion object {
        const val DEFAULT_ACTOR = "huggable_quote~tabelog-scraper"
    }
}

private val rowsJson = Json { ignoreUnknownKeys = true }

/** The actor's rows (one per review, restaurant fields repeated) as restaurants in order and their reviews by id. */
internal fun parseTabelogRows(body: String): Pair<List<TabelogHit>, Map<String, List<TabelogReview>>> {
    val rows = rowsJson.parseToJsonElement(body) as? JsonArray ?: return emptyList<TabelogHit>() to emptyMap()
    val hits = LinkedHashMap<String, TabelogHit>()
    val reviews = LinkedHashMap<String, MutableList<TabelogReview>>()
    rows.forEach { e ->
        val r = e as? JsonObject ?: return@forEach
        val id = r.text("restaurantId").ifEmpty { r.text("restaurantUrl") }.ifEmpty { return@forEach }
        hits.getOrPut(id) {
            TabelogHit(
                id = id,
                name = r.text("restaurantName"),
                url = r.text("restaurantUrl"),
                score = r.number("restaurantRating")?.takeIf { it in 0.0..5.0 },
                reviewCount = r.number("totalReviewCount")?.toInt(),
                genre = r.text("restaurantGenre"),
                lat = r.number("latitude"),
                lng = r.number("longitude"),
                budgetLunch = r.text("budgetLunch"),
                budgetDinner = r.text("budgetDinner"),
                hours = r.text("businessHours"),
                reservation = r.text("reservation"),
                station = r.text("nearestStation"),
            )
        }
        val list = reviews.getOrPut(id) { mutableListOf() }
        val text = r.text("reviewText")
        if (text.isNotEmpty()) list += TabelogReview(r.text("reviewTitle"), text, r.text("reviewDate").ifEmpty { r.text("visitDate") })
    }
    return hits.values.toList() to reviews
}

private fun JsonObject.text(k: String): String =
    (this[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.trim()?.takeIf { it != "null" && it != "None" }.orEmpty()

private fun JsonObject.number(k: String): Double? = text(k).replace(",", "").toDoubleOrNull()
