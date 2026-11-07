package de.wanderlogplus.app.ui.transit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "08:05" from a picker's hour and minute. */
fun pickedTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/** "Do., 20. Mai 2027" for an ISO date; anything else as it is. */
fun dateLabel(iso: String): String = try {
    LocalDate.parse(iso.trim()).format(DateTimeFormatter.ofPattern("EEE, d. MMMM yyyy", Locale.GERMAN))
} catch (e: Exception) {
    iso
}

/** The ISO date of a date picker's selection (UTC midnight). */
fun pickedDate(utcMillis: Long): String = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()

/** UTC midnight of an ISO date (the date picker's initial value); null when it is no date. */
fun dateMillis(iso: String): Long? = try {
    LocalDate.parse(iso.trim()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
} catch (e: Exception) {
    null
}

/** A read-only field that opens a picker on tap ([tag] is on the tap target). */
@Composable
private fun PickerField(label: String, value: String, tag: String, modifier: Modifier, onOpen: () -> Unit) {
    Box(modifier) {
        OutlinedTextField(value, {}, label = { Text(label) }, singleLine = true, readOnly = true, modifier = Modifier.fillMaxWidth())
        // The text field would take the tap for focus: a transparent layer on top opens the picker instead.
        Box(Modifier.matchParentSize().clickable(onClick = onOpen).testTag(tag))
    }
}

/** Datum (calendar) and Uhrzeit (24 h clock) of the transit search; both only through the pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeFields(date: String, time: String, onDate: (String) -> Unit, onTime: (String) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerField("Datum", dateLabel(date), "transit-date", Modifier.weight(1.5f)) { pickDate = true }
        PickerField("Uhrzeit", normalizeTime(time) ?: time, "transit-time", Modifier.weight(1f)) { pickTime = true }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateMillis(date) ?: dateMillis(LocalDate.now().toString()))
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = { state.selectedDateMillis?.let { onDate(pickedDate(it)) }; pickDate = false }, modifier = Modifier.testTag("transit-date-ok")) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Abbrechen") } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val (h, m) = (normalizeTime(time) ?: "09:00").split(":").map { it.toInt() }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        Dialog(onDismissRequest = { pickTime = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Uhrzeit", style = MaterialTheme.typography.labelLarge)
                    TimePicker(state)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pickTime = false }) { Text("Abbrechen") }
                        TextButton(onClick = { onTime(pickedTime(state.hour, state.minute)); pickTime = false }, modifier = Modifier.testTag("transit-time-ok")) { Text("OK") }
                    }
                }
            }
        }
    }
}
