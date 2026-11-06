package de.wanderlogplus.app.ui.attachments

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.app.ui.design.HeaderImages
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.WebPhoto
import de.wanderlogplus.shared.sync.WebPhotoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** Search for photos online via the own server (Google, Wikimedia Commons). */
typealias WebPhotoSearch = suspend (query: String, near: Pair<Double, Double>?) -> List<WebPhoto>

/** Image bytes of a web photo at most this wide. */
typealias WebPhotoFetch = suspend (id: String, width: Int) -> ByteArray

/** What to search for: the text in the field and where (bias). */
data class PhotoQuery(val text: String, val near: Pair<Double, Double>?)

/**
 * One "Fotos hinzufügen": where the photos go, what to search for, and whether only one is wanted (header image).
 * [onAdded] gets the ids of the attachments that were created.
 */
data class PhotoRequest(
    val target: AttachmentTarget,
    val query: PhotoQuery,
    val single: Boolean,
    /** True: straight to the online search (header image), no "Aus Galerie / Online suchen" first. */
    val onlineOnly: Boolean = false,
    val onAdded: suspend (List<String>) -> Unit = {},
)

/** "<title> (Foto: Google).jpg" / "<title> (Foto: Wikimedia · <credit>).jpg"; at most 120 characters before ".jpg". */
fun photoFileName(p: WebPhoto): String {
    val title = p.title.replace(Regex("[/\\\\:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().ifBlank { "Foto" }
    val credit = when (p.source) {
        WebPhotoSource.GOOGLE -> "Foto: Google"
        WebPhotoSource.WIKIMEDIA -> listOf("Foto: Wikimedia", p.credit.replace(Regex("[/\\\\:*?\"<>|]"), " ").trim()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    val tail = " ($credit)"
    val name = if (title.length + tail.length <= 120) title + tail else title.take((120 - tail.length).coerceAtLeast(10)).trimEnd() + tail
    return name.take(120) + ".jpg"
}

/** A web photo as an attachment source: fetched in large size when the photo is stored. */
class WebPhotoAttachment(private val photo: WebPhoto, private val fetch: WebPhotoFetch) : AttachmentSource {
    override fun fileName(): String = photoFileName(photo)
    override fun mimeType(): String = "image/jpeg"
    override fun open(): java.io.InputStream = runBlocking { fetch(photo.id, FULL_WIDTH) }.inputStream()

    companion object {
        const val FULL_WIDTH = 1600
        const val THUMB_WIDTH = 400
    }
}

/** The search text for photos of [target]: place + region, stay + region, or the region of the day. */
fun photoQuery(snapshot: TripSnapshot, target: AttachmentTarget): PhotoQuery {
    fun regionName(id: String?) = id?.let { r -> snapshot.regions.firstOrNull { it.id == r }?.name }
    return when (target) {
        is AttachmentTarget.Day -> {
            val q = snapshot.queries()
            val region = regionName(q?.regionFor(target.date))
            val day = snapshot.days.firstOrNull { it.date == target.date }
            val first = day?.let { d -> snapshot.dayItems.filter { it.dayId == d.id && it.kind == DayItemKind.PLACE }.firstNotNullOfOrNull { snapshot.places[it.placeId] } }
            PhotoQuery(region ?: snapshot.trip?.title.orEmpty(), first?.let { it.lat to it.lng })
        }
        is AttachmentTarget.Entity -> when (target.type) {
            EntityType.SAVED_PLACE -> {
                val s = snapshot.savedPlaces.firstOrNull { it.id == target.id }
                val p = s?.let { snapshot.places[it.placeId] }
                PhotoQuery(listOfNotNull(p?.name?.ifBlank { null }, regionName(s?.regionId)).joinToString(" "), p?.let { it.lat to it.lng })
            }
            EntityType.STAY -> {
                val st = snapshot.stays.firstOrNull { it.id == target.id }
                val p = st?.let { snapshot.places[it.placeId] }
                PhotoQuery(listOfNotNull(p?.name?.ifBlank { null }, regionName(st?.regionId)).joinToString(" "), p?.let { it.lat to it.lng })
            }
            else -> PhotoQuery("", null)
        }
    }
}

/** Sets the header of [type]/[id] to the photo attachment [attachmentId] (looked up in the snapshot of the edit). */
fun headerFromAttachmentEdits(type: EntityType, id: String, attachmentId: String): (LocalEditor, TripSnapshot) -> Unit = { e, s ->
    s.attachments.firstOrNull { it.id == attachmentId }?.fileHash?.takeIf { it.isNotBlank() }?.let { hash ->
        e.set(type, id, mapOf("headerImage" to JsonPrimitive(HeaderImages.OWN + hash)))
    }
}

/**
 * The flow of one [request]: "Aus Galerie" / "Online suchen" (unless [PhotoRequest.onlineOnly]), then the search sheet.
 * [onGallery] opens the system picker, [onPicked] stores the chosen photos, [onDone] closes the flow.
 */
@Composable
fun WebPhotoFlow(
    request: PhotoRequest?,
    search: WebPhotoSearch,
    thumb: suspend (WebPhoto) -> ByteArray?,
    onGallery: (PhotoRequest) -> Unit,
    onPicked: (PhotoRequest, List<WebPhoto>) -> Unit,
    onDone: () -> Unit,
) {
    val r = request ?: return
    var online by remember(r) { mutableStateOf(r.onlineOnly) }
    if (!online) {
        EditSheet("Fotos hinzufügen", onDismiss = onDone) {
            FilledTonalButton(onClick = { onDone(); onGallery(r) }, modifier = Modifier.fillMaxWidth().testTag("photo-source-gallery")) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Aus Galerie")
            }
            FilledTonalButton(onClick = { online = true }, modifier = Modifier.fillMaxWidth().testTag("photo-source-online")) {
                Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Online suchen")
            }
        }
    } else {
        WebPhotoSearchSheet(
            r.query.text, r.query.near, multiple = !r.single, search = search, thumb = thumb,
            onPick = { picked -> onDone(); if (picked.isNotEmpty()) onPicked(r, picked) },
            onDismiss = onDone,
        )
    }
}

/**
 * "Fotos online suchen": search field (pre-filled with [initialQuery]), a 3-column grid of previews, tap selects
 * ([multiple] false: only one), source and credit of the last selected photo, „Übernehmen (n)“.
 */
@Composable
fun WebPhotoSearchSheet(
    initialQuery: String,
    near: Pair<Double, Double>?,
    multiple: Boolean,
    search: WebPhotoSearch,
    thumb: suspend (WebPhoto) -> ByteArray?,
    onPick: (List<WebPhoto>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var run by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<WebPhoto>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<List<WebPhoto>>(emptyList()) }
    LaunchedEffect(run) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); return@LaunchedEffect }
        results = null
        results = try {
            search(q, near).also { failed = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            emptyList()
        }
    }
    EditSheet(if (multiple) "Fotos online suchen" else "Foto online suchen", onDismiss = onDismiss, modifier = Modifier.testTag("web-photos")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Suche") }, modifier = Modifier.weight(1f).testTag("web-photos-query"))
            IconButton(onClick = { run++ }, modifier = Modifier.testTag("web-photos-go")) { Icon(Icons.Filled.Search, contentDescription = "Suchen") }
        }
        val list = results
        when {
            list == null -> CircularProgressIndicator(Modifier.padding(8.dp))
            failed -> Text("Die Fotosuche ist gerade nicht erreichbar.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text("Keine Fotos gefunden.", style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            list.orEmpty().withIndex().chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (i, p) ->
                        val selected = p in chosen
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp)) else Modifier)
                                .clickable { chosen = if (!multiple) listOf(p) else if (selected) chosen - p else chosen + p }
                                .testTag("web-photo-$i"),
                        ) {
                            val bytes by produceState<ByteArray?>(null, p.id) {
                                value = try { thumb(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                            }
                            bytes?.let { AsyncImage(model = it, contentDescription = p.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
                            if (selected) {
                                Icon(
                                    Icons.Filled.CheckCircle, contentDescription = "Ausgewählt", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(Color.White, RoundedCornerShape(11.dp)),
                                )
                            }
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        chosen.lastOrNull()?.let { p ->
            Text(
                listOf(p.title, if (p.source == WebPhotoSource.GOOGLE) "Google" else p.credit.ifBlank { "Wikimedia Commons" }).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onPick(chosen) }, enabled = chosen.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("web-photos-take"),
        ) { Text(if (multiple) "Übernehmen (${chosen.size})" else "Übernehmen") }
    }
}
