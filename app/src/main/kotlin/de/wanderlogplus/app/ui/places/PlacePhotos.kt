package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import de.wanderlogplus.app.ui.design.PLACE_IMAGE_BASE
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.PlacePhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

/** One photo of the row: a Wanderlog image (by URL) or a Google photo (by name, loaded through the server). */
private sealed interface RowPhoto {
    data class Wanderlog(val key: String) : RowPhoto
    data class Google(val photo: PlacePhoto) : RowPhoto
}

private fun wanderlogUrl(key: String, large: Boolean) =
    if (large) PLACE_IMAGE_BASE.replace("freeImageMedium", "freeImage") + key else PLACE_IMAGE_BASE + key

/**
 * "Fotos vom Ort": the place's Wanderlog images right away, Google photos after "Mehr von Google" (or at once when
 * loaded before), credited to their authors. A tap opens the photos full screen.
 */
@Composable
fun PlacePhotosRow(place: Place?, modifier: Modifier = Modifier) {
    val repo = LocalPlacePhotos.current
    val gid = place?.googlePlaceId?.takeIf { it.isNotBlank() }
    var google by remember(gid) { mutableStateOf<List<PlacePhoto>?>(null) }
    var loading by remember(gid) { mutableStateOf(false) }
    var note by remember(gid) { mutableStateOf<String?>(null) }
    var viewing by remember(gid) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo, gid) { if (repo != null && gid != null) repo.peek(gid)?.let { google = it } }

    val photos = place?.imageKeys.orEmpty().filter { it.isNotBlank() }.map { RowPhoto.Wanderlog(it.trim()) } +
        google.orEmpty().map { RowPhoto.Google(it) }

    Column(modifier.fillMaxWidth().testTag("place-photos"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Fotos vom Ort", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            photos.forEachIndexed { i, p ->
                val m = Modifier.size(width = 132.dp, height = 99.dp).clip(RoundedCornerShape(8.dp)).clickable { viewing = i }.testTag("place-photo-$i")
                when (p) {
                    is RowPhoto.Wanderlog -> AsyncImage(wanderlogUrl(p.key, large = false), contentDescription = null, contentScale = ContentScale.Crop, modifier = m.background(MaterialTheme.colorScheme.surfaceVariant))
                    is RowPhoto.Google -> GoogleImage(repo, p.photo, PlacePhotoRepository.THUMB_WIDTH, m.background(MaterialTheme.colorScheme.surfaceVariant), ContentScale.Crop)
                }
            }
            if (repo != null && gid != null && google == null) {
                if (loading) {
                    Box(Modifier.size(width = 132.dp, height = 99.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                } else {
                    OutlinedButton(
                        onClick = {
                            loading = true
                            note = null
                            scope.launch {
                                when (val r = repo.load(gid)) {
                                    is PhotosResult.Photos -> { google = r.photos; if (r.photos.isEmpty()) note = "Google hat keine Fotos zu diesem Ort." }
                                    PhotosResult.NotConfigured -> note = "Google-Fotos sind auf dem Server nicht eingerichtet."
                                    PhotosResult.Failed -> note = "Google-Fotos gerade nicht erreichbar."
                                }
                                loading = false
                            }
                        },
                        modifier = Modifier.height(99.dp).testTag("place-photos-google"),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Mehr von\nGoogle")
                    }
                }
            }
        }
        if (photos.isEmpty() && (repo == null || gid == null)) {
            Text("Keine Fotos vom Ort.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("place-photos-note")) }
        google?.map { it.author }?.filter { it.isNotBlank() }?.distinct()?.takeIf { it.isNotEmpty() }?.let { authors ->
            Text(
                "Google-Fotos: " + authors.joinToString(", "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.testTag("place-photos-credit"),
            )
        }
    }

    viewing?.let { start -> PhotoViewer(photos, start, repo) { viewing = null } }
}

@Composable
internal fun GoogleImage(repo: PlacePhotoRepository?, photo: PlacePhoto, width: Int, modifier: Modifier, scale: ContentScale) {
    val file by produceState<File?>(null, repo, photo.name, width) { value = repo?.file(photo.name, width) }
    if (file != null) AsyncImage(file, contentDescription = photo.author.takeIf { it.isNotBlank() }?.let { "Foto von $it" }, contentScale = scale, modifier = modifier)
    else Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
}

/** Full-screen pager over the row's photos, with the author of a Google photo. */
@Composable
private fun PhotoViewer(photos: List<RowPhoto>, start: Int, repo: PlacePhotoRepository?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("place-photo-viewer")) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                when (val p = photos[i]) {
                    is RowPhoto.Wanderlog -> AsyncImage(wanderlogUrl(p.key, large = true), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    is RowPhoto.Google -> Box(Modifier.fillMaxSize()) {
                        GoogleImage(repo, p.photo, PlacePhotoRepository.FULL_WIDTH, Modifier.fillMaxSize(), ContentScale.Fit)
                        if (p.photo.author.isNotBlank()) {
                            Text(
                                "Foto: ${p.photo.author} · Google", color = Color.White, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                            )
                        }
                    }
                }
            }
            Text(
                "${pager.currentPage + 1} / ${photos.size}", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("place-photo-close")) {
                Icon(Icons.Filled.Close, contentDescription = "Schließen", tint = Color.White)
            }
        }
    }
}
