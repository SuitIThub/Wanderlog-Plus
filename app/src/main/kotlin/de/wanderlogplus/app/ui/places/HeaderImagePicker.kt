package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.app.ui.design.HeaderImages
import de.wanderlogplus.app.ui.design.placeImageUrl
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.PlacePhoto
import kotlinx.coroutines.launch

/** A candidate for the header: its reference and how to draw the thumbnail. */
private sealed interface Candidate {
    val ref: String
    data class Wanderlog(val key: String) : Candidate { override val ref get() = HeaderImages.WANDERLOG + key }
    data class Own(val photo: Attachment) : Candidate { override val ref get() = HeaderImages.OWN + photo.fileHash }
    data class Google(val photo: PlacePhoto) : Candidate { override val ref get() = HeaderImages.GOOGLE + photo.name }
}

/**
 * "Titelbild ändern": every photo of the place – Wanderlog's, own ones ([own]) and Google's (loaded on request) – to
 * pick as header; "Standardbild" ([onPick] with null) goes back to the first place image. A Google photo is stored on
 * the device in full size before it is picked, so the header shows offline.
 */
@Composable
fun HeaderImagePicker(
    place: Place?,
    own: List<Attachment>,
    files: AttachmentFiles?,
    current: String?,
    onPick: (String?) -> Unit,
    onUpload: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val repo = LocalPlacePhotos.current
    val gid = place?.googlePlaceId?.takeIf { it.isNotBlank() }
    var google by remember(gid) { mutableStateOf<List<PlacePhoto>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo, gid) { if (repo != null && gid != null) repo.peek(gid)?.let { google = it } }

    val candidates = place?.imageKeys.orEmpty().filter { it.isNotBlank() }.map { Candidate.Wanderlog(it.trim()) } +
        own.map { Candidate.Own(it) } + google.orEmpty().map { Candidate.Google(it) }

    EditSheet("Titelbild", onDismiss = onDismiss) {
        if (candidates.isEmpty()) Text("Noch keine Fotos – lade eins hoch.", style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth > 600.dp) 5 else 3
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.chunked(columns).forEachIndexed { row, chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEachIndexed { col, c ->
                            val i = row * columns + col
                            val selected = c.ref == current
                            val m = Modifier.weight(1f).aspectRatio(4f / 3f).clip(RoundedCornerShape(8.dp))
                                .then(if (selected) Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp)) else Modifier)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = !busy) {
                                    if (c is Candidate.Google && repo != null) {
                                        busy = true
                                        scope.launch {
                                            val f = repo.file(c.photo.name, PlacePhotoRepository.FULL_WIDTH)
                                            busy = false
                                            if (f != null) onPick(c.ref) else note = "Das Google-Foto ließ sich gerade nicht laden."
                                        }
                                    } else {
                                        onPick(c.ref)
                                    }
                                }
                                .testTag("header-pick-$i")
                            Box(m) {
                                when (c) {
                                    is Candidate.Wanderlog -> AsyncImage(placeImageUrl(c.key), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    is Candidate.Own -> AsyncImage(files?.fileFor(c.photo.fileHash), contentDescription = c.photo.fileName, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    is Candidate.Google -> GoogleImage(repo, c.photo, PlacePhotoRepository.THUMB_WIDTH, Modifier.matchParentSize(), ContentScale.Crop)
                                }
                                if (selected) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = "Gewählt", tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.White, RoundedCornerShape(50)).size(22.dp))
                                }
                            }
                        }
                        repeat(columns - chunk.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onUpload != null) {
                FilledTonalButton(onClick = onUpload, modifier = Modifier.testTag("header-upload")) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Foto hochladen")
                }
            }
            if (repo != null && gid != null && google == null) {
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            when (val r = repo.load(gid)) {
                                is PhotosResult.Photos -> google = r.photos
                                PhotosResult.NotConfigured -> note = "Google-Fotos sind auf dem Server nicht eingerichtet."
                                PhotosResult.Failed -> note = "Google-Fotos gerade nicht erreichbar."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("header-google"),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Mehr von Google")
                }
            }
        }
        if (onUpload != null) Text("Hochgeladene Fotos erscheinen hier und bei den eigenen Fotos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onPick(null) }, enabled = current != null, modifier = Modifier.testTag("header-default")) { Text("Standardbild") }
        }
    }
}
