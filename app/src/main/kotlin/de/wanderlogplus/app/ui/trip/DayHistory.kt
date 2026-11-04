package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.DayVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("dd.MM. HH:mm")

/** "04.11. 18:30" in the device's zone. */
internal fun versionTime(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = WHEN.format(Instant.ofEpochMilli(at).atZone(zone))

/**
 * "Verlauf" of a day: its saved versions (before a chat plan was applied or a version restored), newest first, each
 * with its program in short; "Wiederherstellen" brings one back (the current program is saved as a version first).
 */
@Composable
internal fun DayHistoryDialog(snapshot: TripSnapshot, history: List<DayVersion>, onRestore: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verlauf des Tages") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("day-history")) {
                Text(
                    "Gespeichert wird vor jedem übernommenen Chat-Plan und vor jedem Wiederherstellen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                history.sortedByDescending { it.at }.forEach { v ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${versionTime(v.at)} · ${v.label}", style = MaterialTheme.typography.labelLarge)
                            val names = v.items.map { i ->
                                (i.time?.let { "$it " } ?: "") + if (i.kind == DayItemKind.PLACE) snapshot.places[i.placeId]?.name ?: "Ort" else "Notiz"
                            }
                            Text(
                                names.joinToString(" · ").ifEmpty { "leeres Programm" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onRestore(v.at) }, modifier = Modifier.testTag("day-restore-${v.at}")) { Text("Wiederherstellen") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
