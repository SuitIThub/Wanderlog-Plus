package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.data.SimulatedTime
import de.wanderlogplus.app.data.simulatedTimeOf

/**
 * "Heute simulieren" in Einstellungen (debug mode): a date and optionally a time the Heute screen pretends.
 * Tags: `sim-date`, `sim-time`, `sim-apply`, `sim-off`, `sim-message`.
 */
@Composable
fun SimulationSettings(current: SimulatedTime?, onChange: (SimulatedTime?) -> Unit) {
    var date by rememberSaveable(current) { mutableStateOf(current?.date.orEmpty()) }
    var time by rememberSaveable(current) { mutableStateOf(current?.time.orEmpty()) }
    var problem by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Heute simulieren", style = MaterialTheme.typography.titleSmall)
        Text(
            current?.let { "Aktiv: " + it.label() } ?: "Aus: „Heute“ zeigt den echten Tag.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(date, { date = it; problem = false }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true, modifier = Modifier.weight(1.5f).testTag("sim-date"))
            OutlinedTextField(time, { time = it; problem = false }, label = { Text("Uhrzeit") }, placeholder = { Text("echt") }, singleLine = true, modifier = Modifier.weight(1f).testTag("sim-time"))
        }
        if (problem) {
            Text("Datum als 2027-05-20, Uhrzeit als 09:30 oder leer.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sim-message"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { simulatedTimeOf(date, time)?.let(onChange) ?: run { problem = true } }, modifier = Modifier.testTag("sim-apply")) { Text("Übernehmen") }
            if (current != null) TextButton(onClick = { onChange(null) }, modifier = Modifier.testTag("sim-off")) { Text("Aus") }
        }
    }
}

/** Over the Heute screen while a day is simulated: which one, a day back/forward, and off. Tag `sim-bar`. */
@Composable
fun SimulationBar(current: SimulatedTime, onChange: (SimulatedTime?) -> Unit) {
    Surface(color = Color(0xFFFFE0B2), contentColor = Color(0xFF5D4037), modifier = Modifier.fillMaxWidth().testTag("sim-bar")) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(current.shiftDays(-1)) }, modifier = Modifier.testTag("sim-prev")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Einen Tag zurück")
            }
            Text("Simuliert: " + current.label(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(current.shiftDays(1)) }, modifier = Modifier.testTag("sim-next")) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Einen Tag vor")
            }
            TextButton(onClick = { onChange(null) }) { Text("Aus") }
        }
    }
}
