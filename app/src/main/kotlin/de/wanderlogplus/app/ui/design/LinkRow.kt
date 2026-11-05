package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.shared.model.CustomLink

fun linkKindIcon(kind: LinkKind): ImageVector = when (kind) {
    LinkKind.NAVIGATE -> Icons.Filled.Directions
    LinkKind.TRANSIT -> Icons.Filled.DirectionsTransit
    LinkKind.WEBSITE -> Icons.Filled.Language
    LinkKind.PHONE -> Icons.Filled.Call
    LinkKind.TABELOG -> Icons.Filled.Restaurant
    LinkKind.CUSTOM -> Icons.Filled.Link
}

/** "booking.com/x" → "https://booking.com/x"; blank stays blank. */
fun normalizeUrl(url: String): String {
    val u = url.trim()
    return when {
        u.isBlank() -> ""
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u) -> u
        else -> "https://$u"
    }
}

/** A label for a link without one: the host ("booking.com"). */
fun linkLabel(l: CustomLink): String = l.label.trim().ifBlank {
    normalizeUrl(l.url).substringAfter("://").substringBefore('/').removePrefix("www.").ifBlank { "Link" }
}

fun customExternalLinks(links: List<CustomLink>): List<ExternalLink> =
    links.filter { it.url.isNotBlank() }.map { ExternalLink(LinkKind.CUSTOM, linkLabel(it), normalizeUrl(it.url)) }

/**
 * Buttons of a page: the built-in links ([builtIn]: navigation, website …), then the travellers' own [custom] links
 * and – with [onSetLinks] – "Link" to add or edit them. Nothing when there is nothing to show or edit.
 */
@Composable
fun LinkRow(
    builtIn: List<ExternalLink>,
    custom: List<CustomLink>,
    onOpen: ((ExternalLink) -> Unit)?,
    onSetLinks: ((List<CustomLink>) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    val own = customExternalLinks(custom)
    if ((onOpen == null || builtIn.isEmpty() && own.isEmpty()) && onSetLinks == null) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp).testTag("link-row"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onOpen != null) {
            builtIn.forEach { l -> ActionCircle(linkKindIcon(l.kind), l.label, { onOpen(l) }, "link-${l.kind}") }
            own.forEachIndexed { i, l -> ActionCircle(Icons.Filled.Link, l.label, { onOpen(l) }, "link-custom-$i") }
        }
        if (onSetLinks != null) {
            ActionCircle(Icons.Filled.AddLink, if (custom.isEmpty()) "Link" else "Links", { editing = true }, "link-edit")
        }
    }
    if (editing && onSetLinks != null) {
        LinksSheet(custom, onSave = { onSetLinks(it); editing = false }, onDismiss = { editing = false })
    }
}

/** Edit the own links: name and URL per row, add and remove. */
@Composable
fun LinksSheet(initial: List<CustomLink>, onSave: (List<CustomLink>) -> Unit, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(initial.ifEmpty { listOf(CustomLink()) }) }
    EditSheet("Links", onDismiss = onDismiss) {
        list.forEachIndexed { i, l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        l.label, { v -> list = list.mapIndexed { j, x -> if (j == i) x.copy(label = v) else x } },
                        label = { Text("Name (z. B. Tickets)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("link-$i-label"),
                    )
                    OutlinedTextField(
                        l.url, { v -> list = list.mapIndexed { j, x -> if (j == i) x.copy(url = v) else x } },
                        label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("link-$i-url"),
                    )
                }
                IconButton(onClick = { list = list.filterIndexed { j, _ -> j != i } }, modifier = Modifier.testTag("link-$i-remove")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Entfernen")
                }
            }
        }
        OutlinedButton(onClick = { list = list + CustomLink() }, modifier = Modifier.testTag("link-add")) { Text("＋ Weiterer Link") }
        Text("Ohne „https://“ wird es ergänzt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { onSave(list.map { CustomLink(it.label.trim(), normalizeUrl(it.url)) }.filter { it.url.isNotBlank() }) },
                modifier = Modifier.testTag("link-save"),
            ) { Text("Speichern") }
        }
    }
}
