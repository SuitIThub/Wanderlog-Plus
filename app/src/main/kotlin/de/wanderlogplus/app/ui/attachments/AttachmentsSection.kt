package de.wanderlogplus.app.ui.attachments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import java.io.File
import java.util.Locale

sealed interface OpenTarget {
    data class Image(val file: File) : OpenTarget
    data class Pdf(val file: File) : OpenTarget
    data class External(val file: File, val mimeType: String) : OpenTarget
    object Unavailable : OpenTarget
}

fun openTargetFor(a: Attachment, files: AttachmentFiles): OpenTarget {
    val file = files.fileFor(a.fileHash) ?: return OpenTarget.Unavailable
    val mime = a.mimeType.lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> OpenTarget.Image(file)
        mime == "application/pdf" -> OpenTarget.Pdf(file)
        else -> OpenTarget.External(file, a.mimeType.ifBlank { "application/octet-stream" })
    }
}

/** "1,2 MB" (one decimal from MB), "332 KB", "12 B"; base 1024. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_064L -> "${Math.round(bytes / 1024.0)} KB" // from here KB would round up to 1024
    else -> String.format(Locale.GERMAN, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * What the screens need to show and edit attachments; null in a screen means "no attachments UI" (tests, previews).
 * [onAdd] targets an existing entity, [onAddToDay] a day by date (the Day is created first if missing).
 */
class AttachmentActions(
    val available: (hash: String) -> Boolean,
    val onAdd: (EntityType, String) -> Unit,
    val onAddToDay: (date: String) -> Unit,
    val onOpen: (Attachment) -> Unit,
    val onDelete: (Attachment) -> Unit,
    /** Picks photos for the gallery of an entity / a day; null: no gallery uploads. */
    val onAddPhotos: ((EntityType, String) -> Unit)? = null,
    val onAddPhotosToDay: ((date: String) -> Unit)? = null,
    /** Local files, for thumbnails. */
    val files: de.wanderlogplus.app.files.AttachmentFiles? = null,
    /**
     * Online photo search straight away (header image: [single] true); [onAdded] gets the new attachments' ids.
     * Null: no online search (no server).
     */
    val onSearchPhotos: ((target: AttachmentTarget, single: Boolean, onAdded: suspend (List<String>) -> Unit) -> Unit)? = null,
)

@Composable
fun AttachmentsSection(
    attachments: List<Attachment>,
    available: (String) -> Boolean,
    onAdd: () -> Unit,
    onOpen: (Attachment) -> Unit,
    onDelete: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    /** False when the screen already shows its own "Anhänge" label above the section. */
    showTitle: Boolean = true,
) {
    var confirm by remember { mutableStateOf<Attachment?>(null) }
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showTitle) Text("Anhänge", style = MaterialTheme.typography.titleSmall)
        attachments.forEach { a ->
            var menu by remember(a.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f).clickable { onOpen(a) }.padding(vertical = 6.dp).testTag("attachment-${a.id}"),
                ) {
                    Text(a.fileName.ifBlank { "Anhang" }, style = MaterialTheme.typography.bodyMedium)
                    val hint = if (available(a.fileHash)) null else "nicht offline verfügbar"
                    Text(
                        listOfNotNull(formatBytes(a.size), hint).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hint == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("attachment-menu-${a.id}")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Löschen") }, onClick = { menu = false; confirm = a })
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.testTag("attachment-add")) {
            Icon(Icons.Filled.AttachFile, contentDescription = null)
            Text("Anhang hinzufügen", Modifier.padding(start = 4.dp))
        }
    }
    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Anhang löschen?") },
            text = { Text(a.fileName.ifBlank { "Anhang" }) },
            confirmButton = {
                TextButton(onClick = { confirm = null; onDelete(a) }, modifier = Modifier.testTag("attachment-delete-confirm")) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } },
        )
    }
}

/** The section wired to [actions] for one entity; nothing when [actions] is null. */
@Composable
fun AttachmentsFor(
    attachments: List<Attachment>,
    actions: AttachmentActions?,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    if (actions == null) return
    AttachmentsSection(attachments, actions.available, onAdd, actions.onOpen, actions.onDelete, modifier, showTitle)
}
