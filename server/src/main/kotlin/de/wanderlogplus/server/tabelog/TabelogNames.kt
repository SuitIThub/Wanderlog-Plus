package de.wanderlogplus.server.tabelog

import de.wanderlogplus.server.ai.distanceKm
import de.wanderlogplus.shared.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A restaurant's Japanese name (what Tabelog's search understands) and its prefecture as Tabelog area slug. */
data class JaName(val name: String, val area: String)

/** Finds the Japanese name of a place; null when unknown. */
fun interface JaNameSource {
    fun find(place: Place): JaName?
}

/** Google Places in Japanese: details by id, or a text search around the place. */
interface GoogleJaTransport {
    fun details(id: String): Pair<Int, String>
    fun search(body: String): Pair<Int, String>
}

/** How far a search hit may be from the place, in km. */
private const val NEAR_KM = 0.3

/** Japanese name and prefecture from Google: the place's own entry when it has one, else the nearest text-search hit. */
class GoogleJaNames(private val transport: GoogleJaTransport) : JaNameSource {
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(place: Place): JaName? = try {
        if (place.googlePlaceId.isNotBlank()) {
            val (status, body) = transport.details(place.googlePlaceId)
            if (status !in 200..299) null else nameOf(json.parseToJsonElement(body).jsonObject)
        } else {
            searchNear(place)
        }
    } catch (e: Exception) {
        null
    }

    private fun searchNear(place: Place): JaName? {
        if (place.name.isBlank()) return null
        val body = buildJsonObject {
            put("textQuery", place.name)
            put("languageCode", "ja")
            put("regionCode", "JP")
            put("pageSize", 3)
            putJsonObject("locationBias") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", place.lat); put("longitude", place.lng) }
                    put("radius", NEAR_KM * 1000)
                }
            }
        }.toString()
        val (status, text) = transport.search(body)
        if (status !in 200..299) return null
        val places = json.parseToJsonElement(text).jsonObject["places"] as? JsonArray ?: return null
        return places.mapNotNull { it as? JsonObject }.firstOrNull { p ->
            val loc = p["location"] as? JsonObject ?: return@firstOrNull false
            val lat = (loc["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@firstOrNull false
            val lng = (loc["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@firstOrNull false
            distanceKm(place.lat, place.lng, lat, lng) <= NEAR_KM
        }?.let(::nameOf)
    }

    private fun nameOf(p: JsonObject): JaName? {
        val name = ((p["displayName"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val address = (p["formattedAddress"] as? JsonPrimitive)?.content.orEmpty()
        return JaName(name, prefectureSlug(address))
    }
}

/** Google Places over HTTP, Japanese, only name, address and position. */
class HttpGoogleJaTransport(private val apiKey: String) : GoogleJaTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun details(id: String): Pair<Int, String> = send(
        HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/places/" + URLEncoder.encode(id, "UTF-8") + "?languageCode=ja"))
            .header("X-Goog-FieldMask", "displayName,formattedAddress,location").GET(),
    )

    override fun search(body: String): Pair<Int, String> = send(
        HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/places:searchText"))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)),
    )

    private fun send(b: HttpRequest.Builder): Pair<Int, String> = try {
        http.send(b.timeout(Duration.ofSeconds(20)).header("X-Goog-Api-Key", apiKey).build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            .let { it.statusCode() to it.body() }
    } catch (e: IOException) {
        503 to ""
    }
}

/** The 47 prefectures (Japanese name without 都/道/府/県) and Tabelog's area slug. */
internal val PREFECTURES: Map<String, String> = linkedMapOf(
    "北海" to "hokkaido", "青森" to "aomori", "岩手" to "iwate", "宮城" to "miyagi", "秋田" to "akita", "山形" to "yamagata",
    "福島" to "fukushima", "茨城" to "ibaraki", "栃木" to "tochigi", "群馬" to "gunma", "埼玉" to "saitama", "千葉" to "chiba",
    "東京" to "tokyo", "神奈川" to "kanagawa", "新潟" to "niigata", "富山" to "toyama", "石川" to "ishikawa", "福井" to "fukui",
    "山梨" to "yamanashi", "長野" to "nagano", "岐阜" to "gifu", "静岡" to "shizuoka", "愛知" to "aichi", "三重" to "mie",
    "滋賀" to "shiga", "京都" to "kyoto", "大阪" to "osaka", "兵庫" to "hyogo", "奈良" to "nara", "和歌山" to "wakayama",
    "鳥取" to "tottori", "島根" to "shimane", "岡山" to "okayama", "広島" to "hiroshima", "山口" to "yamaguchi", "徳島" to "tokushima",
    "香川" to "kagawa", "愛媛" to "ehime", "高知" to "kochi", "福岡" to "fukuoka", "佐賀" to "saga", "長崎" to "nagasaki",
    "熊本" to "kumamoto", "大分" to "oita", "宮崎" to "miyazaki", "鹿児島" to "kagoshima", "沖縄" to "okinawa",
)

private val PREFECTURE_IN_ADDRESS = Regex("""(北海道|東京都|京都府|大阪府|神奈川県|和歌山県|鹿児島県|[一-龯]{2}県)""")

/** Tabelog area slug of the first prefecture in a Japanese address; "all" when none is found. */
internal fun prefectureSlug(jaAddress: String): String {
    val m = PREFECTURE_IN_ADDRESS.find(jaAddress)?.value ?: return "all"
    val key = if (m == "北海道") "北海" else m.dropLast(1)
    return PREFECTURES[key] ?: "all"
}
