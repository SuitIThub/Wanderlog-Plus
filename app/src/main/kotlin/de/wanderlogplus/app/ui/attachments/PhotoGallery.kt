package de.wanderlogplus.app.ui.attachments

import androidx.compose.foundation.horizontalScroll

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType

const val PHOTOS_ROUTE = "photos/{type}/{targetId}/{index}"

fun photosRoute(type: EntityType, targetId: String, index: Int) = "photos/${type.name}/${android.net.Uri.encode(targetId)}/$index"

/** "1 Foto", "12 Fotos". */
fun photoCountLabel(n: Int) = if (n == 1) "1 Foto" else "$n Fotos"

/**
 * The gallery of a place or a day: square thumbnails, three per row (four when wide), and "Fotos hinzufügen".
 * Photos not downloaded yet show a cloud. Tags: `photo-gallery`, `photo-<i>`, `photo-add`.
 */
@Composable
fun PhotoGrid(
    photos: List<Attachment>,
    files: AttachmentFiles?,
    onAdd: (() -> Unit)?,
    onOpen: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("photo-gallery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onAdd != null) {
            FilledTonalButton(onClick = onAdd, modifier = Modifier.testTag("photo-add")) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Fotos hinzufügen")
            }
        }
        if (photos.isEmpty()) {
            EmptyHint("Noch keine Fotos.")
            return@Column
        }
        Text(photoCountLabel(photos.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth > 600.dp) 4 else 3
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                photos.chunked(columns).forEachIndexed { row, chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunk.forEachIndexed { col, p ->
                            val i = row * columns + col
                            Thumbnail(p, files, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { onOpen(i) }.testTag("photo-$i"))
                        }
                        repeat(columns - chunk.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/**
 * A row of square thumbnails that scrolls sideways (photos of a stay under its header); [order] are indexes into the
 * full gallery in the order shown, so [onOpen] gets the gallery index. Tags: [tag] and "[tag]-position".
 */
@Composable
fun PhotoStrip(photos: List<Attachment>, order: List<Int>, files: AttachmentFiles?, onOpen: (index: Int) -> Unit, tag: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        order.forEachIndexed { pos, i ->
            photos.getOrNull(i)?.let { p ->
                Thumbnail(p, files, Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpen(i) }.testTag("$tag-$pos"))
            }
        }
    }
}

@Composable
private fun Thumbnail(photo: Attachment, files: AttachmentFiles?, modifier: Modifier) {
    val file = files?.fileFor(photo.fileHash)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (file != null) {
            AsyncImage(model = file, contentDescription = photo.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.CloudDownload, contentDescription = "Noch nicht geladen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Full-screen photos of one gallery, swiped sideways, starting at [start]; delete with confirmation.
 * Tags: `photo-viewer`, `photo-page-<i>`, `photo-delete`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photos: List<Attachment>,
    start: Int,
    files: AttachmentFiles?,
    onDelete: ((Attachment) -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            EmptyHint("Keine Fotos.", Modifier.padding(24.dp))
        }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
    var confirm by rememberSaveable { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(Color.Black).testTag("photo-viewer")) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
            val file = files?.fileFor(photos[i].fileHash)
            Box(Modifier.fillMaxSize().testTag("photo-page-$i"), contentAlignment = Alignment.Center) {
                if (file != null) {
                    AsyncImage(model = file, contentDescription = photos[i].fileName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text("Foto wird noch geladen …", color = Color.White)
                }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White) }
            Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, modifier = Modifier.weight(1f))
            if (onDelete != null) {
                IconButton(onClick = { confirm = true }, modifier = Modifier.testTag("photo-delete")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Foto löschen", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
    if (confirm && onDelete != null) {
        val photo = photos.getOrNull(pager.currentPage)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Foto löschen?") },
            text = { Text("Das Foto verschwindet auch auf den anderen Geräten.") },
            confirmButton = {
                TextButton(onClick = { confirm = false; photo?.let(onDelete); if (photos.size <= 1) onBack() }, modifier = Modifier.testTag("photo-delete-confirm")) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
        )
    }
}
