package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.ui.design.PLACE_IMAGE_BASE
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.PlacePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Outcome of asking for a place's Google photos. */
sealed interface PhotosResult {
    data class Photos(val photos: List<PlacePhoto>) : PhotosResult
    data object NotConfigured : PhotosResult
    data object Failed : PhotosResult
}

/**
 * Google photos of places via the sync server, kept on disk (list and images) so they show offline once loaded.
 * Thumbnails are [THUMB_WIDTH] wide, the full view [FULL_WIDTH].
 */
class PlacePhotoRepository(private val api: suspend () -> SyncApi?, private val dir: File) {
    private val lists = ConcurrentHashMap<String, List<PlacePhoto>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(PlacePhoto.serializer())

    private fun key(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    /** The stored list, without network; null when never loaded. */
    suspend fun peek(googlePlaceId: String): List<PlacePhoto>? = lists[googlePlaceId] ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(listSer, File(dir, "list-${key(googlePlaceId)}.json").readText()) }.getOrNull()?.also { lists[googlePlaceId] = it }
    }

    suspend fun load(googlePlaceId: String): PhotosResult {
        peek(googlePlaceId)?.let { return PhotosResult.Photos(it) }
        val a = api() ?: return PhotosResult.NotConfigured
        return try {
            val photos = a.placePhotos(googlePlaceId)
            lists[googlePlaceId] = photos
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                File(dir, "list-${key(googlePlaceId)}.json").writeText(json.encodeToString(listSer, photos))
            }
            PhotosResult.Photos(photos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.kind == ApiErrorKind.NOT_CONFIGURED) PhotosResult.NotConfigured else PhotosResult.Failed
        } catch (e: Exception) {
            PhotosResult.Failed
        }
    }

    /** The photo's file when it is already on this device (no network). */
    fun cachedFile(name: String, width: Int): File? = File(dir, "img-${key("$name|$width")}.jpg").takeIf { it.exists() && it.length() > 0 }

    /** The photo as a local file (fetched once); null when it cannot be loaded now. */
    suspend fun file(name: String, width: Int): File? = withContext(Dispatchers.IO) {
        val f = File(dir, "img-${key("$name|$width")}.jpg")
        if (f.exists() && f.length() > 0) return@withContext f
        val a = api() ?: return@withContext null
        try {
            val bytes = a.placePhoto(name, width)
            dir.mkdirs()
            val tmp = File(dir, f.name + ".part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
            f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val THUMB_WIDTH = 400
        const val FULL_WIDTH = 1600
    }
}

/** Google photos of places; null hides "Mehr von Google" (tests, no server). */
val LocalPlacePhotos = staticCompositionLocalOf<PlacePhotoRepository?> { null }

fun wanderlogUrl(key: String, large: Boolean) =
    if (large) PLACE_IMAGE_BASE.replace("freeImageMedium", "freeImage") + key else PLACE_IMAGE_BASE + key

@Composable
fun GoogleImage(repo: PlacePhotoRepository?, photo: PlacePhoto, width: Int, modifier: Modifier, scale: ContentScale) {
    val file by produceState<File?>(null, repo, photo.name, width) { value = repo?.file(photo.name, width) }
    if (file != null) AsyncImage(file, contentDescription = photo.author.takeIf { it.isNotBlank() }?.let { "Foto von $it" }, contentScale = scale, modifier = modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
}
