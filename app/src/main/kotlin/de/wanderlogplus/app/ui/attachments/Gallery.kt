package de.wanderlogplus.app.ui.attachments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.HeaderImages
import de.wanderlogplus.app.ui.places.GoogleImage
import de.wanderlogplus.app.ui.places.LocalPlacePhotos
import de.wanderlogplus.app.ui.places.PhotosResult
import de.wanderlogplus.app.ui.places.PlacePhotoRepository
import de.wanderlogplus.app.ui.places.wanderlogUrl
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.PlacePhoto
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** The device's attachment files, for screens without [AttachmentActions] (e.g. "Bewerten"); null in tests. */
val LocalAttachmentFiles = androidx.compose.runtime.staticCompositionLocalOf<AttachmentFiles?> { null }

/** One photo of a place's, a stay's or a day's collection, whatever its source. */
sealed interface GalleryPhoto {
    /** The header reference of this photo ("att:", "wl:", "g:"). */
    val ref: String
    /** Stable identity in the collection (two own photos may share a file). */
    val key: String

    /** An own photo (uploaded, found online or adopted): synced and on every device. */
    data class Own(val photo: Attachment) : GalleryPhoto {
        override val ref get() = HeaderImages.OWN + photo.fileHash
        override val key get() = "id:" + photo.id
    }

    /** An image of the imported Wanderlog place. */
    data class Wanderlog(val imageKey: String) : GalleryPhoto {
        override val ref get() = HeaderImages.WANDERLOG + imageKey
        override val key get() = ref
    }

    /** A borrowed Google photo: only on this device until it is adopted. */
    data class Google(val photo: PlacePhoto) : GalleryPhoto {
        override val ref get() = HeaderImages.GOOGLE + photo.name
        override val key get() = ref
    }
}

/**
 * The photos of one place, stay or day in the order shown: the header, own photos (a stay's room photos first),
 * Wanderlog's images, then Google's photos not adopted yet. [headerRef] is the photo the header shows (own choice or
 * default); [googlePlaceId] null: Google has nothing for it.
 */
data class Gallery(val photos: List<GalleryPhoto>, val headerRef: String?, val googlePlaceId: String?, val placeName: String) {
    fun indexOfOwn(attachmentId: String) = photos.indexOfFirst { it is GalleryPhoto.Own && it.photo.id == attachmentId }
    val isEmpty get() = photos.isEmpty()
}

/** The place whose Wanderlog and Google photos belong to [type]/[id]; null for days and other things. */
fun galleryPlace(snapshot: TripSnapshot, type: EntityType?, id: String): Place? = when (type) {
    EntityType.SAVED_PLACE -> snapshot.savedPlaces.firstOrNull { it.id == id }?.let { snapshot.places[it.placeId] }
    EntityType.STAY -> snapshot.stays.firstOrNull { it.id == id }?.let { snapshot.places[it.placeId] }
    else -> null
}

/**
 * The collection of [type]/[id] with the Google photos loaded so far ([google]). [orderBy] puts that photo first
 * instead of the header (the full-screen viewer keeps its order while the header changes).
 */
fun galleryOf(snapshot: TripSnapshot, type: EntityType?, id: String, google: List<PlacePhoto>?, orderBy: String? = null): Gallery {
    val own = type?.let { snapshot.photosFor(it, id) }.orEmpty()
    val place = galleryPlace(snapshot, type, id)
    val explicit = when (type) {
        EntityType.SAVED_PLACE -> snapshot.savedPlaces.firstOrNull { it.id == id }?.headerImage
        EntityType.STAY -> snapshot.stays.firstOrNull { it.id == id }?.headerImage
        else -> null
    }?.takeIf { it.isNotBlank() }
    val ownSorted = if (type == EntityType.STAY) own.sortedBy { if (it.label == Attachment.LABEL_ROOM) 0 else 1 } else own
    val keys = place?.imageKeys.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val gid = place?.googlePlaceId?.takeIf { it.isNotBlank() }
    val adopted = own.mapNotNull { it.origin }.toSet()
    val borrowed = if (gid == null) emptyList() else google.orEmpty().filter { HeaderImages.GOOGLE + it.name !in adopted }
    val all = ownSorted.map { GalleryPhoto.Own(it) } + keys.map { GalleryPhoto.Wanderlog(it) } + borrowed.map { GalleryPhoto.Google(it) }
    val header = explicit ?: when (type) {
        EntityType.STAY -> ownSorted.firstOrNull { it.label == Attachment.LABEL_ROOM }?.let { HeaderImages.OWN + it.fileHash }
            ?: keys.firstOrNull()?.let { HeaderImages.WANDERLOG + it }
        EntityType.SAVED_PLACE -> keys.firstOrNull()?.let { HeaderImages.WANDERLOG + it } ?: own.firstOrNull()?.let { HeaderImages.OWN + it.fileHash }
        else -> null
    }
    val first = all.firstOrNull { it.ref == (orderBy ?: header) }
    return Gallery(listOfNotNull(first) + all.filter { it !== first }, header, gid, place?.name.orEmpty())
}

/** "<place> (Foto: <author> · Google).jpg" for an adopted Google photo. */
fun googlePhotoFileName(placeName: String, photo: PlacePhoto): String {
    fun clean(s: String) = s.replace(Regex("[/\\\\:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
    val title = clean(placeName).ifBlank { "Foto" }
    val author = clean(photo.author)
    val credit = if (author.isBlank()) "Foto: Google" else "Foto: $author · Google"
    val tail = " ($credit)"
    return (if (title.length + tail.length <= 120) title + tail else title.take((120 - tail.length).coerceAtLeast(10)).trimEnd() + tail).take(120) + ".jpg"
}

/** A borrowed Google photo as an attachment source: the full-size file, fetched once through the server. */
class GooglePhotoSource(private val repo: PlacePhotoRepository, private val photo: PlacePhoto, private val placeName: String) : AttachmentSource {
    override fun fileName(): String = googlePhotoFileName(placeName, photo)
    override fun mimeType(): String = "image/jpeg"
    override fun open(): java.io.InputStream? = runBlocking { repo.file(photo.name, PlacePhotoRepository.FULL_WIDTH) }?.inputStream()
}

/** Google's photos of one place for this screen: loaded on request ("Mehr von Google"), cached on the device. */
@Stable
class GoogleGalleryState internal constructor(val repo: PlacePhotoRepository?, val googlePlaceId: String?) {
    var photos by mutableStateOf<List<PlacePhoto>?>(null)
    var loading by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    val canLoad get() = repo != null && googlePlaceId != null && photos == null

    suspend fun load() {
        val r = repo ?: return
        val gid = googlePlaceId ?: return
        loading = true
        note = null
        when (val res = r.load(gid)) {
            is PhotosResult.Photos -> { photos = res.photos; if (res.photos.isEmpty()) note = "Google hat keine Fotos zu diesem Ort." }
            PhotosResult.NotConfigured -> note = "Google-Fotos sind auf dem Server nicht eingerichtet."
            PhotosResult.Failed -> note = "Google-Fotos gerade nicht erreichbar."
        }
        loading = false
    }
}

/** The collection of [type]/[id] for a screen, with its Google state (already loaded photos show at once). */
class GalleryUi(val gallery: Gallery, val google: GoogleGalleryState)

@Composable
fun rememberGallery(snapshot: TripSnapshot, type: EntityType?, id: String): GalleryUi {
    val repo = LocalPlacePhotos.current
    val gid = galleryPlace(snapshot, type, id)?.googlePlaceId?.takeIf { it.isNotBlank() }
    val google = remember(repo, gid) { GoogleGalleryState(repo, gid) }
    LaunchedEffect(google) { if (repo != null && gid != null) repo.peek(gid)?.let { google.photos = it } }
    val gallery = remember(snapshot, type, id, google.photos) { galleryOf(snapshot, type, id, google.photos) }
    return GalleryUi(gallery, google)
}

/** Any photo of a collection: thumbnail ([large] false) or full size. */
@Composable
fun GalleryImage(photo: GalleryPhoto, files: AttachmentFiles?, large: Boolean, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    when (photo) {
        is GalleryPhoto.Own -> {
            val file = files?.fileFor(photo.photo.fileHash)
            if (file != null) {
                AsyncImage(model = file, contentDescription = photo.photo.fileName, contentScale = scale, modifier = modifier)
            } else {
                Box(modifier, contentAlignment = Alignment.Center) {
                    if (large) Text("Foto wird noch geladen …", color = Color.White)
                    else Icon(Icons.Filled.CloudDownload, contentDescription = "Noch nicht geladen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is GalleryPhoto.Wanderlog -> AsyncImage(wanderlogUrl(photo.imageKey, large), contentDescription = null, contentScale = scale, modifier = modifier)
        is GalleryPhoto.Google -> GoogleImage(
            LocalPlacePhotos.current, photo.photo,
            if (large) PlacePhotoRepository.FULL_WIDTH else PlacePhotoRepository.THUMB_WIDTH, modifier, scale,
        )
    }
}

/** Where a borrowed photo comes from; null for own photos. */
private fun sourceLabel(p: GalleryPhoto) = when (p) {
    is GalleryPhoto.Own -> null
    is GalleryPhoto.Wanderlog -> "Wanderlog"
    is GalleryPhoto.Google -> "Google"
}

@Composable
private fun Tile(photo: GalleryPhoto, files: AttachmentFiles?, isHeader: Boolean, modifier: Modifier, ticked: Boolean? = null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        GalleryImage(photo, files, large = false, modifier = Modifier.fillMaxSize())
        sourceLabel(photo)?.let {
            Text(
                it, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        if (isHeader) {
            Icon(
                Icons.Filled.Image, contentDescription = "Titelbild", tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(3.dp).size(14.dp),
            )
        }
        if (ticked != null) {
            Icon(
                if (ticked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (ticked) "Gewählt" else "Nicht gewählt",
                tint = if (ticked) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                    .background(if (ticked) Color.White else Color.Black.copy(alpha = 0.25f), CircleShape),
            )
        }
    }
}

/**
 * The whole collection: "Fotos hinzufügen", square tiles (three per row, four when wide) with their source, the header
 * marked, then "Mehr von Google" as last tile. [selected]: picking own photos (room photos) – only own photos show a
 * tick and a tap still goes to [onOpen]. [highlightHeader] false hides the header mark (picking a new one).
 * Tags: `photo-gallery`, `photo-<i>`, `photo-add`, `photo-google`, `photo-google-note`, `photo-google-credit`.
 */
@Composable
fun GalleryGrid(
    gallery: Gallery,
    google: GoogleGalleryState?,
    files: AttachmentFiles?,
    onAdd: (() -> Unit)?,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    selected: Set<String>? = null,
    highlightHeader: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val photos = gallery.photos
    val googleTile = google?.canLoad == true || google?.loading == true
    Column(modifier.fillMaxWidth().testTag("photo-gallery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onAdd != null) {
            FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("photo-add")) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Fotos hinzufügen")
            }
        }
        if (photos.isEmpty() && !googleTile) {
            EmptyHint("Noch keine Fotos.")
        }
        if (photos.isNotEmpty()) {
            Text(photoCountLabel(photos.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val cells: List<Int?> = photos.indices.toList() + (if (googleTile && selected == null) listOf(null) else emptyList())
        if (cells.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth > 600.dp) 4 else 3
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(columns).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunk.forEach { i ->
                            val cell = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                            if (i == null) {
                                Surface(
                                    onClick = { scope.launch { google?.load() } },
                                    enabled = google?.loading != true,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = cell.testTag("photo-google"),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (google?.loading == true) {
                                            CircularProgressIndicator(Modifier.size(28.dp))
                                        } else {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                                                Text("Mehr von\nGoogle", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                }
                            } else {
                                val p = photos[i]
                                val ticked = if (selected != null && p is GalleryPhoto.Own) p.photo.id in selected else null
                                Tile(
                                    p, files, isHeader = highlightHeader && selected == null && p.ref == gallery.headerRef,
                                    modifier = cell.clickable(enabled = selected == null || p is GalleryPhoto.Own) { onOpen(i) }.testTag("photo-$i"),
                                    ticked = ticked,
                                )
                            }
                        }
                        repeat(columns - chunk.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        google?.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("photo-google-note"))
        }
        photos.filterIsInstance<GalleryPhoto.Google>().map { it.photo.author }.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.let { authors ->
            Text(
                "Google-Fotos: " + authors.joinToString(", "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                modifier = Modifier.testTag("photo-google-credit"),
            )
        }
    }
}

/** The first photos of a collection in a row that scrolls sideways, with "Alle"; tags [tag] and "[tag]-<i>". */
@Composable
fun GalleryStrip(gallery: Gallery, files: AttachmentFiles?, onOpen: (index: Int) -> Unit, onAll: (() -> Unit)?, tag: String, modifier: Modifier = Modifier) {
    if (gallery.isEmpty) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            gallery.photos.take(STRIP_SIZE).forEachIndexed { i, p ->
                Tile(p, files, isHeader = false, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpen(i) }.testTag("$tag-$i"))
            }
        }
        if (onAll != null) TextButton(onClick = onAll, modifier = Modifier.testTag("$tag-all")) { Text("Alle") }
    }
}

const val STRIP_SIZE = 8

/** "Tippe das neue Titelbild an" with "Standardbild" and "Abbrechen" above the collection while picking a header. */
@Composable
fun HeaderPickBar(onDefault: (() -> Unit)?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth().testTag("header-pick-bar")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tippe das neue Titelbild an", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onDefault != null) TextButton(onClick = onDefault, modifier = Modifier.testTag("header-default")) { Text("Standardbild") }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("header-cancel")) { Text("Abbrechen") }
        }
    }
}

/** What the full-screen viewer offers for the photo shown; null hides an action. */
class GalleryViewerActions(
    val onSetHeader: ((GalleryPhoto) -> Unit)? = null,
    val onKeep: ((GalleryPhoto.Google) -> Unit)? = null,
    val onToggleRoom: ((Attachment) -> Unit)? = null,
    val onDelete: ((Attachment) -> Unit)? = null,
)

/**
 * Full-screen photos of one collection, swiped sideways, starting at [start]. Per photo: "Als Titelbild",
 * "Behalten" (Google), "Zimmerfoto" (own photos of a stay) and delete (own photos, with confirmation); a Google
 * photo names its author. When a photo is adopted the pager stays on it.
 * Tags: `photo-viewer`, `photo-page-<i>`, `photo-set-header`, `photo-keep`, `photo-room`, `photo-delete`.
 */
@Composable
fun GalleryViewerScreen(
    gallery: Gallery,
    start: Int,
    files: AttachmentFiles?,
    actions: GalleryViewerActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val photos = gallery.photos
    if (photos.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            EmptyHint("Keine Fotos.", Modifier.padding(24.dp))
        }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
    var shownKey by remember { mutableStateOf(photos.getOrNull(pager.currentPage)?.key) }
    LaunchedEffect(pager.currentPage) { shownKey = photos.getOrNull(pager.currentPage)?.key }
    // The list changed (photo adopted or deleted): stay on the same photo, or on its adopted copy.
    LaunchedEffect(photos) {
        val key = shownKey ?: return@LaunchedEffect
        var i = photos.indexOfFirst { it.key == key }
        if (i < 0 && key.startsWith(HeaderImages.GOOGLE)) i = photos.indexOfFirst { it is GalleryPhoto.Own && it.photo.origin == key }
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
    }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val current = photos.getOrNull(pager.currentPage.coerceIn(0, photos.lastIndex))
    Box(modifier.fillMaxSize().background(Color.Black).testTag("photo-viewer")) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            Box(Modifier.fillMaxSize().testTag("photo-page-$i"), contentAlignment = Alignment.Center) {
                GalleryImage(photos[i], files, large = true, modifier = Modifier.fillMaxSize(), scale = ContentScale.Fit)
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White) }
            Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, modifier = Modifier.weight(1f))
            if (current != null) ViewerActions(current, current.ref == gallery.headerRef, actions) { confirm = true }
        }
        val credit = when (current) {
            is GalleryPhoto.Google -> listOf(current.photo.author.takeIf { it.isNotBlank() }?.let { "Foto: $it" }, "Google").filterNotNull().joinToString(" · ")
            is GalleryPhoto.Wanderlog -> "Wanderlog"
            else -> null
        }
        credit?.let {
            Text(it, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp))
        }
    }
    val delete = actions.onDelete
    if (confirm && delete != null) {
        val photo = (current as? GalleryPhoto.Own)?.photo
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Foto löschen?") },
            text = { Text("Das Foto verschwindet auch auf den anderen Geräten.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; photo?.let(delete); if (photos.size <= 1) onBack() }, modifier = Modifier.testTag("photo-delete-confirm")) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ViewerActions(photo: GalleryPhoto, isHeader: Boolean, actions: GalleryViewerActions, onAskDelete: () -> Unit) {
    actions.onSetHeader?.let { set ->
        IconButton(onClick = { set(photo) }, enabled = !isHeader, modifier = Modifier.testTag("photo-set-header")) {
            Icon(
                if (isHeader) Icons.Filled.CheckCircle else Icons.Filled.Image,
                contentDescription = if (isHeader) "Ist das Titelbild" else "Als Titelbild",
                tint = if (isHeader) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
    if (photo is GalleryPhoto.Google) actions.onKeep?.let { keep ->
        IconButton(onClick = { keep(photo) }, modifier = Modifier.testTag("photo-keep")) {
            Icon(Icons.Filled.DownloadForOffline, contentDescription = "Behalten", tint = Color.White)
        }
    }
    if (photo is GalleryPhoto.Own) {
        actions.onToggleRoom?.let { toggle ->
            val room = photo.photo.label == Attachment.LABEL_ROOM
            IconButton(onClick = { toggle(photo.photo) }, modifier = Modifier.testTag("photo-room")) {
                Icon(
                    if (room) Icons.Filled.Bed else Icons.Outlined.Bed,
                    contentDescription = if (room) "Kein Zimmerfoto mehr" else "Als Zimmerfoto",
                    tint = if (room) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }
        if (actions.onDelete != null) {
            IconButton(onClick = onAskDelete, modifier = Modifier.testTag("photo-delete")) {
                Icon(Icons.Filled.Delete, contentDescription = "Foto löschen", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/**
 * The viewer route of [type]/[targetId]: rebuilds the collection (with the Google photos on this device) in the
 * order it had when opened, and wires the actions to [attachments].
 */
@Composable
fun GalleryViewerRoute(
    snapshot: TripSnapshot,
    type: EntityType?,
    targetId: String,
    start: Int,
    files: AttachmentFiles?,
    attachments: AttachmentActions?,
    onBack: () -> Unit,
) {
    val repo = LocalPlacePhotos.current
    val gid = galleryPlace(snapshot, type, targetId)?.googlePlaceId?.takeIf { it.isNotBlank() }
    // Null until the stored Google list was looked up, so [start] points at the same photo as on the screen.
    val google by produceState<List<PlacePhoto>?>(if (repo == null || gid == null) emptyList() else null, repo, gid) {
        value = if (repo != null && gid != null) repo.peek(gid).orEmpty() else emptyList()
    }
    val loaded = google ?: return
    val orderBy = remember(type, targetId) { galleryOf(snapshot, type, targetId, loaded).photos.firstOrNull()?.ref }
    val gallery = galleryOf(snapshot, type, targetId, loaded, orderBy)
    val withHeader = type == EntityType.SAVED_PLACE || type == EntityType.STAY
    val adopt = attachments?.onAdopt
    GalleryViewerScreen(
        gallery, start, files,
        GalleryViewerActions(
            onSetHeader = if (withHeader && type != null) attachments?.onSetHeader?.let { set ->
                { p: GalleryPhoto ->
                    if (p is GalleryPhoto.Google && adopt != null && repo != null) adopt(type, targetId, GooglePhotoSource(repo, p.photo, gallery.placeName), p.ref, true)
                    else set(type, targetId, p.ref)
                }
            } else null,
            onKeep = if (type != null && adopt != null && repo != null) ({ p -> adopt(type, targetId, GooglePhotoSource(repo, p.photo, gallery.placeName), p.ref, false) }) else null,
            onToggleRoom = if (type == EntityType.STAY) attachments?.onSetLabel?.let { set ->
                { a: Attachment -> set(a.id, if (a.label == Attachment.LABEL_ROOM) null else Attachment.LABEL_ROOM) }
            } else null,
            onDelete = attachments?.onDelete,
        ),
        onBack,
    )
}

/**
 * Picks [photo] as the header of [type]/[id] on a screen: a Google photo is adopted first (when possible), the others
 * are referenced directly through [onSetHeader].
 */
fun pickHeader(
    photo: GalleryPhoto,
    type: EntityType,
    id: String,
    gallery: Gallery,
    repo: PlacePhotoRepository?,
    attachments: AttachmentActions?,
    onSetHeader: (String) -> Unit,
) {
    val adopt = attachments?.onAdopt
    if (photo is GalleryPhoto.Google && adopt != null && repo != null) adopt(type, id, GooglePhotoSource(repo, photo.photo, gallery.placeName), photo.ref, true)
    else onSetHeader(photo.ref)
}
