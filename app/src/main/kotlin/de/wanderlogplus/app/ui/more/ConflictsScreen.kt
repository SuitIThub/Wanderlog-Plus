package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.client.Conflict
import de.wanderlogplus.client.TripSnapshot
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_CHARS = 120

/** Strings without quotes, everything else as compact JSON. */
fun conflictValueText(v: JsonElement): String = if (v is JsonPrimitive) v.content else v.toString()

@Composable
fun ConflictsScreen(
    snapshot: TripSnapshot,
    onKeepApp: (Conflict) -> Unit,
    onTakeWanderlog: (Conflict) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshot.conflicts.isEmpty()) {
        Text("Keine Konflikte", modifier.padding(16.dp).testTag("conflicts-empty"))
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("conflicts-list")) {
        items(snapshot.conflicts, key = { "${it.type}/${it.id}/${it.field}" }) { c ->
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.label, style = MaterialTheme.typography.titleMedium)
                Text(c.field, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ValueRow("App:", c.appValue)
                ValueRow("Wanderlog:", c.wanderlogValue)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onKeepApp(c) }) { Text("App behalten") }
                    Button(onClick = { onTakeWanderlog(c) }) { Text("Wanderlog übernehmen") }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ValueRow(title: String, value: JsonElement) {
    val full = conflictValueText(value)
    var expanded by rememberSaveable(full) { mutableStateOf(false) }
    val long = full.length > MAX_CHARS
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(if (long && !expanded) full.take(MAX_CHARS) + "…" else full, style = MaterialTheme.typography.bodyMedium)
        if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "weniger" else "mehr") }
    }
}
