package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SurfaceCard
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
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
        EmptyHint("Keine Konflikte", modifier.padding(16.dp).testTag("conflicts-empty"))
        return
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("conflicts-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(snapshot.conflicts, key = { "${it.type}/${it.id}/${it.field}" }) { c ->
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column {
                        Text(c.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(c.field, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValueBox("App:", c.appValue, Modifier.weight(1f))
                        ValueBox("Wanderlog:", c.wanderlogValue, Modifier.weight(1f))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onKeepApp(c) }, modifier = Modifier.fillMaxWidth()) { Text("App behalten") }
                        Button(onClick = { onTakeWanderlog(c) }, modifier = Modifier.fillMaxWidth()) { Text("Wanderlog übernehmen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueBox(title: String, value: JsonElement, modifier: Modifier = Modifier) {
    val full = conflictValueText(value)
    var expanded by rememberSaveable(full) { mutableStateOf(false) }
    val long = full.length > MAX_CHARS
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(if (long && !expanded) full.take(MAX_CHARS) + "…" else full, style = MaterialTheme.typography.bodyMedium)
            if (long) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "weniger" else "mehr") }
        }
    }
}
