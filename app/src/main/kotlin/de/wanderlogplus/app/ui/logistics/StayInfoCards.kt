package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.AiJobInfo

/** "Do, 14.05. ab 15:00" (check-in) or "Sa, 16.05. bis 10:00" (check-out); only the day without a time, "–" without a date. */
fun stayTimeText(date: String, time: String?, checkIn: Boolean): String {
    if (date.isBlank()) return "–"
    val t = time?.trim()?.takeIf { it.isNotEmpty() } ?: return de.wanderlogplus.app.ui.dayLabel(date)
    return de.wanderlogplus.app.ui.dayLabel(date) + (if (checkIn) " ab " else " bis ") + t
}

/**
 * The room and the property of a stay: what research found or was entered by hand, the room photos, the sources;
 * "Recherchieren" asks for links (the stored ones are used too) and offers "Alles neu recherchieren".
 */
@Composable
fun StayInfoCards(
    stay: Stay,
    roomPhotos: List<Attachment>,
    files: AttachmentFiles?,
    onResearch: ((links: List<String>, fresh: Boolean) -> Unit)?,
    onEdit: (() -> Unit)?,
    running: AiJobInfo?,
    onOpenPhoto: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val info = stay.info
    var asking by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth().testTag("stay-info"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (running != null && running.status != "DONE" && running.status != "FAILED") {
            Text(running.steps.lastOrNull()?.text ?: "Recherche läuft …", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onResearch != null) {
                OutlinedButton(onClick = { asking = true }, modifier = Modifier.testTag("stay-research")) {
                    Text(if (info == null) "Zimmer & Ausstattung recherchieren" else "Recherchieren")
                }
            }
            if (onEdit != null && info != null) OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("stay-info-edit")) { Text("Bearbeiten") }
        }
        info?.let { StayFactTiles(remember(it) { stayFacts(it) }) }
        info?.let { AmenityPanels(it) }
        val room = info?.room
        if (room != null && (room.description.isNotBlank() || roomPhotos.isNotEmpty())) {
            SurfaceCard(Modifier.testTag("stay-room")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Zimmer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (room.type.isNotBlank()) Text(room.type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (room.description.isNotBlank()) Text(room.description, style = MaterialTheme.typography.bodyMedium)
                    if (roomPhotos.isNotEmpty()) {
                        de.wanderlogplus.app.ui.attachments.PhotoGrid(roomPhotos, files, onAdd = null, onOpen = onOpenPhoto)
                    }
                }
            }
        }
        val property = info?.property
        if (property != null && (property.description.isNotBlank() || property.houseRules.isNotBlank())) {
            SurfaceCard(Modifier.testTag("stay-property")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Unterkunft", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (property.description.isNotBlank()) Text(property.description, style = MaterialTheme.typography.bodyMedium)
                    if (property.houseRules.isNotBlank()) Text("Hausregeln: " + property.houseRules)
                }
            }
        }
        if (info != null && info.sources.isNotEmpty()) {
            Column {
                TextButton(onClick = { showSources = !showSources }, modifier = Modifier.testTag("stay-sources")) {
                    Text((if (showSources) "Quellen ausblenden" else "Quellen") + " (${info.sources.size})")
                }
                if (showSources) info.sources.forEach { s -> TextButton(onClick = { runCatching { uri.openUri(s) } }) { Text(hostOf(s)) } }
            }
        }
        if (info == null && onResearch == null) Text("Noch keine Angaben zu Zimmer und Ausstattung.", style = MaterialTheme.typography.bodySmall)
    }
    if (asking && onResearch != null) ResearchDialog(stay.infoLinks, onDismiss = { asking = false }) { links, fresh -> asking = false; onResearch(links, fresh) }
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

@Composable
private fun ResearchDialog(stored: List<String>, onDismiss: () -> Unit, onStart: (List<String>, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var confirmFresh by remember { mutableStateOf(false) }
    fun links() = (stored + text.split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith("http") }).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zimmer & Ausstattung recherchieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gelesen werden die Buchungsbestätigungen unter den Anhängen, diese Links und eine Websuche.", style = MaterialTheme.typography.bodySmall)
                stored.forEach { Text("• " + hostOf(it), style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Weitere Links (Hotelseite, Booking …)") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("stay-research-links"),
                )
                TextButton(onClick = { confirmFresh = true }, modifier = Modifier.testTag("stay-research-fresh")) { Text("Alles neu recherchieren …") }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(links(), false) }, modifier = Modifier.testTag("stay-research-start")) { Text("Recherchieren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
    if (confirmFresh) {
        AlertDialog(
            onDismissRequest = { confirmFresh = false },
            title = { Text("Alles neu recherchieren?") },
            text = { Text("Auch was du von Hand geändert hast, wird durch die neue Recherche ersetzt.") },
            confirmButton = { TextButton(onClick = { confirmFresh = false; onStart(links(), true) }, modifier = Modifier.testTag("stay-research-fresh-confirm")) { Text("Alles ersetzen") } },
            dismissButton = { TextButton(onClick = { confirmFresh = false }) { Text("Abbrechen") } },
        )
    }
}
