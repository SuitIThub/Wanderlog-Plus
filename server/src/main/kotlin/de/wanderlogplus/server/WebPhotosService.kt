package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.WebPhoto
import de.wanderlogplus.shared.sync.WebPhotoSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** GET of a Wikimedia Commons URL; returns status and body. Replaced in tests. */
fun interface WikimediaTransport {
    fun get(url: String): Pair<Int, ByteArray>
}

class HttpWikimediaTransport : WikimediaTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun get(url: String): Pair<Int, ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "WanderlogPlus/1.0 (private trip planner)")
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray()).let { it.statusCode() to it.body() }
    }
}

/** Image bytes and their media type. */
class PhotoBytes(val bytes: ByteArray, val mime: String)

/**
 * "Online suchen" for photos: Google's photos of the best matching places first, then Wikimedia Commons. A source that
 * fails contributes nothing. Images are fetched through [media]: Google's via the place photo proxy, Commons only
 * from `https://upload.wikimedia.org` (never an address from elsewhere). Blocking.
 */
class WebPhotosService(
    private val google: GooglePlacesSearch,
    /** Google photo name and width → stored JPEG (see [PlacePhotosService.media]). */
    private val googleMedia: (String, Int) -> Path,
    private val wiki: WikimediaTransport,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun search(q: String, near: Pair<Double, Double>?): List<WebPhoto> {
        val fromGoogle = try {
            // The best match (usually the place asked for) gives all its photos (Google hands out at most 10), the others a few.
            google.search(q, near, null, GOOGLE_PLACES).flatMapIndexed { i, hit ->
                hit.photos.take(if (i == 0) FIRST_PLACE else PER_PLACE).map { WebPhoto("g:" + it.name, WebPhotoSource.GOOGLE, hit.name, "Google", it.width, it.height) }
            }
        } catch (e: Exception) {
            emptyList()
        }
        val fromWiki = try {
            wikimedia(q)
        } catch (e: Exception) {
            emptyList()
        }
        return (fromGoogle + fromWiki).take(MAX)
    }

    private fun wikimedia(q: String): List<WebPhoto> {
        val url = API + "?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=$WIKI_MAX" +
            "&gsrsearch=" + enc("$q filetype:bitmap") + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=1600"
        val (status, body) = wiki.get(url)
        if (status !in 200..299) return emptyList()
        return pages(body).mapNotNull { page ->
            val title = (page["title"] as? JsonPrimitive)?.content?.removePrefix("File:") ?: return@mapNotNull null
            val info = (page["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val mime = (info["mime"] as? JsonPrimitive)?.content
            if (mime !in IMAGE_TYPES) return@mapNotNull null
            val meta = info["extmetadata"] as? JsonObject
            val artist = meta?.value("Artist")?.let(::stripHtml).orEmpty()
            val license = meta?.value("LicenseShortName")?.let(::stripHtml).orEmpty()
            WebPhoto(
                "w:$title", WebPhotoSource.WIKIMEDIA, title.substringBeforeLast('.'),
                listOf(artist, license).filter { it.isNotBlank() }.joinToString(" · "),
                (info["width"] as? JsonPrimitive)?.intOrNull ?: 0, (info["height"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }.take(WIKI_MAX)
    }

    /** The image of [id] at most [width] pixels wide; [IllegalArgumentException] for an unknown id. */
    fun media(id: String, width: Int): PhotoBytes {
        val w = width.coerceIn(100, 1600)
        return when {
            id.startsWith("g:") -> PhotoBytes(Files.readAllBytes(googleMedia(id.removePrefix("g:"), w)), "image/jpeg")
            id.startsWith("w:") -> wikimediaMedia(id.removePrefix("w:"), w)
            else -> throw IllegalArgumentException("unknown photo id")
        }
    }

    private fun wikimediaMedia(title: String, w: Int): PhotoBytes {
        if (title.isBlank() || title.length > 300) throw IllegalArgumentException("bad title")
        val (status, body) = try {
            wiki.get(API + "?action=query&format=json&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=$w&titles=" + enc("File:$title"))
        } catch (e: Exception) {
            throw PhotosUnavailable()
        }
        if (status !in 200..299) throw PhotosUnavailable()
        val info = try {
            pages(body).firstNotNullOfOrNull { (it["imageinfo"] as? JsonArray)?.firstOrNull() as? JsonObject }
        } catch (e: Exception) {
            null
        } ?: throw PhotosUnavailable()
        val mime = (info["mime"] as? JsonPrimitive)?.content
        val src = ((info["thumburl"] ?: info["url"]) as? JsonPrimitive)?.content ?: throw PhotosUnavailable()
        val uri = try { URI(src) } catch (e: Exception) { throw PhotosUnavailable() }
        if (uri.scheme != "https" || uri.host != UPLOAD_HOST || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) throw PhotosUnavailable()
        val (s, bytes) = try { wiki.get(uri.toString()) } catch (e: Exception) { throw PhotosUnavailable() }
        if (s !in 200..299 || bytes.isEmpty() || bytes.size > MAX_BYTES) throw PhotosUnavailable()
        // The thumbnail of a PNG/WebP can be JPEG; the type from the name is close enough for the app (it re-encodes).
        val type = when {
            src.endsWith(".png", true) -> "image/png"
            src.endsWith(".webp", true) -> "image/webp"
            mime in IMAGE_TYPES && !src.endsWith(".jpg", true) && !src.endsWith(".jpeg", true) -> mime!!
            else -> "image/jpeg"
        }
        return PhotoBytes(bytes, type)
    }

    private fun pages(body: ByteArray): List<JsonObject> {
        val query = json.parseToJsonElement(body.decodeToString()).jsonObject["query"] as? JsonObject ?: return emptyList()
        val pages = query["pages"] as? JsonObject ?: return emptyList()
        // Commons orders search hits by "index"; without it keep the answer's order.
        return pages.values.mapNotNull { it as? JsonObject }.sortedBy { (it["index"] as? JsonPrimitive)?.intOrNull ?: 0 }
    }

    private fun JsonObject.value(key: String): String? = ((this[key] as? JsonObject)?.get("value") as? JsonPrimitive)?.content

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val API = "https://commons.wikimedia.org/w/api.php"
        const val UPLOAD_HOST = "upload.wikimedia.org"
        const val MAX = 30
        const val WIKI_MAX = 12
        const val GOOGLE_PLACES = 5
        const val PER_PLACE = 4
        const val FIRST_PLACE = 10
        const val MAX_BYTES = 15 * 1024 * 1024
        val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        /** Text of a Commons metadata value (often HTML): tags removed, entities for &, <, >, " and spaces decoded. */
        fun stripHtml(s: String): String =
            s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
    }
}
