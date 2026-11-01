package de.wanderlogplus.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

/** A search hit: display [name], a short [detail] line (city, country) and its coordinates. */
data class GeoPlace(val name: String, val detail: String, val lat: Double, val lng: Double)

/**
 * Place search via Photon (OpenStreetMap data, no key). Never sends the server token: this is a third-party service.
 * [near] biases results towards a coordinate (e.g. the trip leg), without restricting them.
 */
class GeoSearch(http: HttpClient, private val baseUrl: String = "https://photon.komoot.io") {
    private val client = http.config {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
        expectSuccess = false
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** Empty list for a blank query; [ApiException] (NETWORK/SERVER) when the service fails. */
    suspend fun search(query: String, near: Pair<Double, Double>? = null, limit: Int = 8): List<GeoPlace> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val response = try {
            client.get("${baseUrl.trimEnd('/')}/api/") {
                parameter("q", q)
                parameter("limit", limit)
                parameter("lang", "de")
                near?.let { (lat, lng) -> parameter("lat", lat); parameter("lon", lng) }
                header(HttpHeaders.UserAgent, "WanderlogPlus")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, cause = e)
        }
        if (response.status.value !in 200..299) throw ApiException(ApiErrorKind.SERVER, status = response.status.value)
        val body = try {
            json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw ApiException(ApiErrorKind.SERVER, message = "Unerwartete Antwort der Ortssuche")
        return parse(body)
    }

    internal fun parse(body: JsonObject): List<GeoPlace> =
        (body["features"] as? JsonArray).orEmpty().mapNotNull { f ->
            val feature = f as? JsonObject ?: return@mapNotNull null
            val coords = ((feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapNotNull null
            val lng = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val props = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
            fun p(key: String) = (props[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val name = p("name") ?: p("street") ?: return@mapNotNull null
            val detail = listOfNotNull(p("city") ?: p("county"), p("state"), p("country")).distinct().joinToString(", ")
            GeoPlace(name, detail, lat, lng)
        }
}
