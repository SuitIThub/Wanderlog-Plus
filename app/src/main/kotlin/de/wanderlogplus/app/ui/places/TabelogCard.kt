package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.model.TabelogInfo
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.model.canonicalTabelogUrl
import java.util.Locale

/** "3,71 · 1.159 Bewertungen"; null without a score. */
internal fun tabelogScoreText(t: TabelogInfo): String? {
    val s = t.score ?: return null
    val count = t.reviewCount?.let { " · " + String.format(Locale.GERMAN, "%,d", it) + " Bewertungen" }.orEmpty()
    return String.format(Locale.GERMAN, "%.2f", s) + count
}

/**
 * Tabelog for a restaurant: score, genre, budget, hours, reservation and the German review summary, with
 * "In Tabelog öffnen". [info] null or REQUESTED: still loading. [onRefresh]/[onSetLink] null hide the menu entries.
 * Tags `tabelog-card`, `tabelog-open`, `tabelog-score`, `tabelog-set-link`, `tabelog-search`.
 */
@Composable
fun TabelogCard(
    info: TabelogInfo?,
    onOpen: () -> Unit,
    onRefresh: (() -> Unit)?,
    onSetLink: ((String) -> Unit)?,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    /** The restaurant has a position: only then the server can match it with Tabelog. */
    hasPosition: Boolean = true,
) {
    var menu by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var showHours by remember { mutableStateOf(false) }
    SurfaceCard(modifier.fillMaxWidth().testTag("tabelog-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tabelog", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onRefresh != null || onSetLink != null) {
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("tabelog-menu")) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (onRefresh != null && info?.status == TabelogStatus.FOUND) DropdownMenuItem(text = { Text("Aktualisieren") }, onClick = { menu = false; onRefresh() })
                            if (onSetLink != null) DropdownMenuItem(text = { Text("Tabelog-Link setzen") }, onClick = { menu = false; linking = true })
                        }
                    }
                }
            }
            when (info?.status) {
                TabelogStatus.FOUND -> {
                    tabelogScoreText(info)?.let { Badge(BadgeSpec(it, if ((info.score ?: 0.0) >= 3.5) BadgeTone.Positive else BadgeTone.Neutral), Modifier.testTag("tabelog-score")) }
                    if (info.name.isNotBlank()) Text(listOf(info.name, info.genre).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    val budget = listOfNotNull(info.budgetLunch.takeIf { it.isNotBlank() }?.let { "Mittags $it" }, info.budgetDinner.takeIf { it.isNotBlank() }?.let { "Abends $it" })
                    if (budget.isNotEmpty()) Text(de.wanderlogplus.app.ui.euroed(budget.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
                    if (info.reservation.isNotBlank()) Text(de.wanderlogplus.app.ui.euroed("Reservierung: " + info.reservation), style = MaterialTheme.typography.bodySmall)
                    if (info.station.isNotBlank()) Text(info.station, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.hours.isNotBlank()) {
                        TextButton(onClick = { showHours = !showHours }) { Text(if (showHours) "Öffnungszeiten ausblenden" else "Öffnungszeiten (Tabelog)") }
                        if (showHours) Text(info.hours, style = MaterialTheme.typography.bodySmall)
                    }
                    if (info.summary.isNotBlank()) Text(de.wanderlogplus.app.ui.euroed(info.summary), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("tabelog-open")) { Text("In Tabelog öffnen") }
                }
                TabelogStatus.NOT_FOUND -> {
                    Text("Bei Tabelog nicht gefunden", style = MaterialTheme.typography.bodyMedium)
                    if (onRefresh != null) TextButton(onClick = onRefresh, modifier = Modifier.testTag("tabelog-retry")) { Text("Neu suchen") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onSetLink != null) OutlinedButton(onClick = { linking = true }, modifier = Modifier.testTag("tabelog-set-link")) { Text("Tabelog-Link setzen") }
                        OutlinedButton(onClick = onSearch, modifier = Modifier.testTag("tabelog-search")) { Text("In Tabelog suchen") }
                    }
                }
                else -> {
                    // Never stuck on "loading": the search is always there, and without a position nothing will come.
                    Text(
                        if (hasPosition) "Tabelog wird geladen …" else "Ohne Position kein Tabelog-Abgleich – Ort mit Google verknüpfen oder den Link setzen.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!hasPosition && onSetLink != null) OutlinedButton(onClick = { linking = true }, modifier = Modifier.testTag("tabelog-set-link")) { Text("Tabelog-Link setzen") }
                        OutlinedButton(onClick = onSearch, modifier = Modifier.testTag("tabelog-search")) { Text("In Tabelog suchen") }
                    }
                }
            }
        }
    }
    if (linking && onSetLink != null) {
        var url by remember { mutableStateOf(info?.url.orEmpty()) }
        // Only a restaurant's own page; a search or list page would give some other restaurant.
        val ok = canonicalTabelogUrl(url) != null
        de.wanderlogplus.app.ui.design.EditSheet("Tabelog-Link setzen", onDismiss = { linking = false }) {
            Text("Die Adresse der Restaurantseite bei Tabelog (https://tabelog.com/…/A1234/A123456/12345678/), z. B. aus der Tabelog-App geteilt.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(url, { url = it.trim() }, singleLine = true, isError = url.isNotEmpty() && !ok, label = { Text("Tabelog-Adresse") }, modifier = Modifier.fillMaxWidth().testTag("tabelog-link-input"))
            Button(onClick = { onSetLink(canonicalTabelogUrl(url) ?: url); linking = false }, enabled = ok, modifier = Modifier.fillMaxWidth().testTag("tabelog-link-save")) { Text("Übernehmen") }
        }
    }
}
