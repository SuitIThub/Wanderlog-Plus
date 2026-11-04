package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.PlacePhoto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** No Google key for place photos is configured. */
class PhotosNotConfigured : RuntimeException("photos not configured")

/** Google could not be reached or answered with an error. */
class PhotosUnavailable : RuntimeException("photos unavailable")

/** Talks to the Places API (New): place details with photos and the photo media. Blocking. */
interface PlacesPhotoTransport {
    /** Status and body of the place details with only the photos field. */
    fun details(googlePlaceId: String): Pair<Int, String>

    /** Status and image bytes of a photo, at most [maxWidth] pixels wide. */
    fun media(name: String, maxWidth: Int): Pair<Int, ByteArray>
}

class GooglePlacesPhotoTransport(private val apiKey: String) : PlacesPhotoTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun details(googlePlaceId: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/places/" + URLEncoder.encode(googlePlaceId, "UTF-8")))
            .timeout(Duration.ofSeconds(20))
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", "photos")
            .GET().build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        } catch (e: IOException) {
            throw PhotosUnavailable()
        }
    }

    override fun media(name: String, maxWidth: Int): Pair<Int, ByteArray> {
        val request = HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/$name/media?maxWidthPx=$maxWidth"))
            .timeout(Duration.ofSeconds(30))
            .header("X-Goog-Api-Key", apiKey)
            .GET().build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray()).let { it.statusCode() to it.body() }
        } catch (e: IOException) {
            throw PhotosUnavailable()
        }
    }
}

/**
 * Photos of a place from Google (up to [MAX_PHOTOS], with the authors to credit). The list is cached for
 * [LIST_TTL] and every photo is stored as a file once per size, so each costs Google quota only once for all devices.
 */
class PlacePhotosService(
    private val store: SqliteStore,
    private val dir: Path,
    private val transport: PlacesPhotoTransport?,
    private val now: () -> Instant = Instant::now,
) {
    val configured: Boolean get() = transport != null

    fun list(googlePlaceId: String): List<PlacePhoto> {
        val t = transport ?: throw PhotosNotConfigured()
        if (!ID.matches(googlePlaceId)) throw BadInput()
        val key = "photos|$googlePlaceId"
        store.cachedRoute(key)?.let { (json, at) ->
            if (Instant.ofEpochMilli(at).plus(LIST_TTL).isAfter(now())) return JSON.decodeFromString(LIST, json)
        }
        val (status, body) = t.details(googlePlaceId)
        val photos = when (status) {
            in 200..299 -> parse(body)
            404 -> emptyList()
            else -> {
                store.cachedRoute(key)?.let { (json, _) -> return JSON.decodeFromString(LIST, json) }
                throw PhotosUnavailable()
            }
        }
        store.putCachedRoute(key, JSON.encodeToString(LIST, photos), now().toEpochMilli())
        return photos
    }

    /** The photo's file at [maxWidth] (fetched once). */
    fun media(name: String, maxWidth: Int): Path {
        val t = transport ?: throw PhotosNotConfigured()
        if (!NAME.matches(name) || maxWidth !in 100..MAX_WIDTH) throw BadInput()
        val file = dir.resolve(sha256("$name|$maxWidth") + ".jpg")
        if (Files.exists(file)) return file
        val (status, bytes) = t.media(name, maxWidth)
        if (status !in 200..299 || bytes.isEmpty()) throw PhotosUnavailable()
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "photo", ".part")
        Files.write(tmp, bytes)
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    companion object {
        const val MAX_PHOTOS = 10
        const val MAX_WIDTH = 1600
        val LIST_TTL: Duration = Duration.ofDays(30)
        private val ID = Regex("[A-Za-z0-9_-]{10,300}")
        private val NAME = Regex("places/[A-Za-z0-9_-]{10,300}/photos/[A-Za-z0-9_-]{10,1000}")
        private val JSON = Json { ignoreUnknownKeys = true }
        private val LIST = ListSerializer(PlacePhoto.serializer())

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun parse(body: String): List<PlacePhoto> {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw PhotosUnavailable()
            return (root["photos"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { NAME.matches(it) } ?: return@mapNotNull null
                val author = ((o["authorAttributions"] as? JsonArray)?.firstOrNull() as? JsonObject)
                PlacePhoto(
                    name = name,
                    width = (o["widthPx"] as? JsonPrimitive)?.intOrNull ?: 0,
                    height = (o["heightPx"] as? JsonPrimitive)?.intOrNull ?: 0,
                    author = (author?.get("displayName") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    authorUri = (author?.get("uri") as? JsonPrimitive)?.contentOrNull,
                )
            }.take(MAX_PHOTOS)
        }
    }
}
