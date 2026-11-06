package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.wanderlogplus.shared.model.Seat
import de.wanderlogplus.shared.seatmap.Cell
import de.wanderlogplus.shared.seatmap.Facility
import de.wanderlogplus.shared.seatmap.SeatMapKind
import de.wanderlogplus.shared.seatmap.SeatMapModel
import de.wanderlogplus.shared.seatmap.SeatMaps
import de.wanderlogplus.shared.seatmap.SeatPosition
import de.wanderlogplus.shared.seatmap.SeatRow
import de.wanderlogplus.shared.seatmap.SeatSection
import de.wanderlogplus.shared.seatmap.seatLine

internal fun positionText(p: SeatPosition) = when (p) {
    SeatPosition.WINDOW -> "Fenster"
    SeatPosition.MIDDLE -> "Mitte"
    SeatPosition.AISLE -> "Gang"
}

/** How many rows around your seats the card shows of an aircraft (the whole cabin is in the full view). */
private const val ROWS_AROUND = 3

/**
 * The "Sitzplätze" card: class, each seat with window/middle/aisle, whether they are side by side, and the drawn plan
 * of [model] with your seats highlighted (aircraft: the rows around them; tap "Ganzer Plan" for the whole cabin).
 */
@Composable
fun SeatCard(model: SeatMapModel?, travelClass: String?, seats: List<Seat>, modifier: Modifier = Modifier) {
    val list = seats.filter { it.seat.isNotBlank() }
    var full by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("seat-card"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (model == null) {
            seatLine(travelClass, list)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text("Für einen Sitzplan unter „Bearbeiten“ den Sitzplan wählen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        travelClass?.trim()?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
        val spots = list.associateWith { SeatMaps.locate(model, it.seat) }
        list.forEach { s ->
            val where = spots[s]?.let { positionText(it.position) } ?: "nicht im Plan"
            Text((s.car?.trim()?.ifBlank { null }?.let { "Wagen $it, " } ?: "") + s.seat.trim() + " · " + where + (s.traveler?.let { " ($it)" } ?: ""))
        }
        val codes = list.map { it.seat.trim().uppercase() }.toSet()
        val together = list.filter { s -> spots[s]?.neighbours?.any { it.uppercase() in codes } == true }.map { it.seat.trim() }
        if (together.size >= 2) Text(together.joinToString(" und ") + " sitzen nebeneinander", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { full = true }, modifier = Modifier.testTag("seatmap-full")) { Text("Ganzer Plan") }
        }
        val highlight = codes
        if (model.kind == SeatMapKind.AIRCRAFT) {
            val mine = model.sections.mapNotNull { section ->
                val idx = section.rows.indices.filter { i -> section.rows[i].cells.any { it is Cell.SeatCell && it.code.uppercase() in highlight } }
                if (idx.isEmpty()) null else section.copy(rows = section.rows.subList(maxOf(0, idx.first() - ROWS_AROUND), minOf(section.rows.size, idx.last() + ROWS_AROUND + 1)))
            }
            mine.forEach { AircraftSection(it, highlight) }
        } else {
            Box(Modifier.horizontalScroll(rememberScrollState())) { TrainCar(model.sections, highlight) }
        }
    }
    if (full && model != null) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { full = false }) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                    }
                    val codes = seats.map { it.seat.trim().uppercase() }.toSet()
                    if (model.kind == SeatMapKind.AIRCRAFT) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { model.sections.forEach { AircraftSection(it, codes) } }
                    } else {
                        Box(Modifier.horizontalScroll(rememberScrollState())) { TrainCar(model.sections, codes) }
                    }
                }
            }
        }
    }
}

private val SEAT = 26.dp

/** An aircraft section: one line per row, front at the top, seats left (A) to right. */
@Composable
private fun AircraftSection(section: SeatSection, highlight: Set<String>) {
    Text(section.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        section.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(22.dp))
                row.cells.forEachIndexed { i, c -> CellView(row, i, c, highlight, label = (c as? Cell.SeatCell)?.code?.removePrefix(row.label)) }
            }
        }
    }
}

/** A train car seen from above: positions from left to right, the double side on top, the single side below. */
@Composable
private fun TrainCar(sections: List<SeatSection>, highlight: Set<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        sections.flatMap { it.rows }.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                row.cells.forEachIndexed { i, c -> CellView(row, i, c, highlight, label = (c as? Cell.SeatCell)?.code) }
            }
        }
    }
}

@Composable
private fun CellView(row: SeatRow, index: Int, cell: Cell, highlight: Set<String>, label: String?) {
    when (cell) {
        is Cell.SeatCell -> {
            val mine = cell.code.uppercase() in highlight
            val aisle = row.cells.getOrNull(index - 1) == Cell.Aisle || row.cells.getOrNull(index + 1) == Cell.Aisle
            val pos = positionText(if (cell.window) SeatPosition.WINDOW else if (aisle) SeatPosition.AISLE else SeatPosition.MIDDLE)
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier.size(SEAT)
                    .background(if (mine) colors.primary else colors.surfaceContainerHigh, RoundedCornerShape(5.dp))
                    .border(1.dp, if (mine) colors.primary else colors.outlineVariant, RoundedCornerShape(5.dp))
                    .semantics { contentDescription = "${cell.code}, $pos" + if (mine) ", euer Platz" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label.orEmpty(), fontSize = 9.sp, color = if (mine) colors.onPrimary else colors.onSurfaceVariant, maxLines = 1)
            }
        }
        Cell.Aisle -> Spacer(Modifier.size(14.dp))
        Cell.Gap -> Spacer(Modifier.size(SEAT))
        is Cell.FacilityCell -> Box(Modifier.size(SEAT), contentAlignment = Alignment.Center) {
            Text(
                when (cell.facility) {
                    Facility.TOILET -> "WC"
                    Facility.GALLEY -> "G"
                    Facility.EXIT -> "⇆"
                    Facility.LUGGAGE -> "🧳"
                    Facility.BABY -> "👶"
                    Facility.WHEELCHAIR -> "♿"
                    Facility.TABLE -> "▭"
                },
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
