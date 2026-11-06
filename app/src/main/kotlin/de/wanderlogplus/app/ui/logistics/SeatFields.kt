package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.shared.model.Seat
import de.wanderlogplus.shared.seatmap.FLIGHT_CLASSES
import de.wanderlogplus.shared.seatmap.SeatMapKind
import de.wanderlogplus.shared.seatmap.SeatMaps
import de.wanderlogplus.shared.seatmap.TRAIN_CLASSES

/** Class (free text with suggestions), the seats (car for trains, seat, person) and the seat map of a ride. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeatFields(train: Boolean, draft: MoveDraft, onChange: (MoveDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            draft.travelClass, { onChange(draft.copy(travelClass = it)) },
            label = { Text("Klasse") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("edit-class"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (if (train) TRAIN_CLASSES else FLIGHT_CLASSES).forEach { c ->
                FilterChip(selected = draft.travelClass == c, onClick = { onChange(draft.copy(travelClass = c)) }, label = { Text(c) })
            }
        }
        draft.seats.forEachIndexed { i, s ->
            fun set(seat: Seat) = onChange(draft.copy(seats = draft.seats.toMutableList().also { it[i] = seat }))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (train) OutlinedTextField(s.car.orEmpty(), { set(s.copy(car = it)) }, label = { Text("Wagen") }, singleLine = true, modifier = Modifier.weight(0.8f).testTag("edit-seat-car-$i"))
                OutlinedTextField(s.seat, { set(s.copy(seat = it)) }, label = { Text("Platz") }, singleLine = true, modifier = Modifier.weight(1f).testTag("edit-seat-$i"))
                OutlinedTextField(s.traveler.orEmpty(), { set(s.copy(traveler = it)) }, label = { Text("Person") }, singleLine = true, modifier = Modifier.weight(1.2f))
                IconButton(onClick = { onChange(draft.copy(seats = draft.seats.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Filled.Close, contentDescription = "Platz entfernen") }
            }
        }
        TextButton(
            onClick = { onChange(draft.copy(seats = draft.seats + Seat(car = draft.seats.lastOrNull()?.car, seat = ""))) },
            modifier = Modifier.testTag("edit-seat-add"),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Platz")
        }
        val kind = if (train) SeatMapKind.TRAIN_CAR else SeatMapKind.AIRCRAFT
        val options = listOf<Pair<String?, String>>(null to "Automatisch (nach Nummer)") + SeatMaps.all.filter { it.kind == kind }.map { it.key to it.name }
        LabeledDropdown(
            tag = "edit-seatmap",
            label = "Sitzplan",
            value = options.firstOrNull { it.first == draft.seatMap }?.second ?: options.first().second,
            options = options,
            onSelect = { onChange(draft.copy(seatMap = it)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
