package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.wanderlogplus.shared.query.DayNow

private const val STEP = 15

/**
 * "Zeit & Dauer" of a program place: a fixed time (switched on it starts with the estimate [estimateStart]) and the
 * stay (default [defaultMinutes], ±15 min). [onSave] gets the fixed time ("HH:mm", null: estimated) and the stay
 * (null: default).
 */
@Composable
fun ItemTimeContent(
    estimateStart: Int,
    defaultMinutes: Int,
    fixedTime: String?,
    stayMinutes: Int?,
    onSave: (String?, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fixed by remember { mutableStateOf(fixedTime?.takeIf { DayNow.mins(it) != null }) }
    var minutes by remember { mutableStateOf(stayMinutes) }
    var pick by remember { mutableStateOf(false) }
    val shown = minutes ?: defaultMinutes
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Zeit & Dauer", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Feste Uhrzeit", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (fixed == null) "Geschätzt: ~" + DayNow.hhmm(estimateStart) else "Bleibt beim Umsortieren",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = fixed != null,
                onCheckedChange = { on -> fixed = if (on) DayNow.hhmm(estimateStart) else null },
                modifier = Modifier.testTag("time-fixed-switch"),
            )
        }
        fixed?.let { f ->
            OutlinedButton(onClick = { pick = true }, modifier = Modifier.testTag("time-fixed-value")) { Text(f) }
        }
        Text("Aufenthalt", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { minutes = (shown - STEP).coerceAtLeast(STEP) }, modifier = Modifier.testTag("time-duration-minus")) {
                Icon(Icons.Filled.Remove, contentDescription = "15 Minuten weniger")
            }
            Text("$shown Min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("time-duration"))
            IconButton(onClick = { minutes = shown + STEP }, modifier = Modifier.testTag("time-duration-plus")) {
                Icon(Icons.Filled.Add, contentDescription = "15 Minuten mehr")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { minutes = null }, enabled = minutes != null, modifier = Modifier.testTag("time-duration-reset")) { Text("Zurücksetzen") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onSave(fixed, minutes) }, modifier = Modifier.testTag("time-save")) { Text("Speichern") }
        }
    }
    if (pick) TimePickDialog(fixed ?: DayNow.hhmm(estimateStart), "Feste Uhrzeit", onPick = { fixed = it; pick = false }, onDismiss = { pick = false })
}

/** [ItemTimeContent] in a bottom sheet; saving closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemTimeSheet(
    estimateStart: Int,
    defaultMinutes: Int,
    fixedTime: String?,
    stayMinutes: Int?,
    onSave: (String?, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ItemTimeContent(estimateStart, defaultMinutes, fixedTime, stayMinutes, onSave = { f, m -> onSave(f, m); onDismiss() }, Modifier.padding(bottom = 24.dp))
    }
}

/** When the day starts at the stay: a time picker with "Zurücksetzen" (09:00). */
@Composable
fun DayStartDialog(current: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    TimePickDialog(current, "Tagesbeginn", onPick = { onSave(it); onDismiss() }, onDismiss = onDismiss, onReset = { onSave(null); onDismiss() })
}

/** A 24-hour time picker in a dialog; [onReset] adds "Zurücksetzen". Tags `day-start-save`, `day-start-reset`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(initial: String, title: String, onPick: (String) -> Unit, onDismiss: () -> Unit, onReset: (() -> Unit)? = null) {
    val start = DayNow.mins(initial) ?: (9 * 60)
    val state = rememberTimePickerState(initialHour = (start / 60) % 24, initialMinute = start % 60, is24Hour = true)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                TimePicker(state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    onReset?.let { TextButton(onClick = it, modifier = Modifier.testTag("day-start-reset")) { Text("Zurücksetzen") } }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }, modifier = Modifier.testTag("day-start-save")) { Text("OK") }
                }
            }
        }
    }
}
