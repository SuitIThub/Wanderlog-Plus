package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsFor
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train

/** One row of the Logistik tab. [date]/[time] are the sort key (depart / check-in). */
sealed interface LogisticsEntry {
    val id: String
    val date: String
    val time: String?

    data class FlightEntry(val flight: Flight) : LogisticsEntry {
        override val id get() = flight.id
        override val date get() = flight.depart.date.orEmpty()
        override val time get() = flight.depart.time
    }

    data class TrainEntry(val train: Train) : LogisticsEntry {
        override val id get() = train.id
        override val date get() = train.depart.date.orEmpty()
        override val time get() = train.depart.time
    }

    data class StayEntry(val stay: Stay, val name: String) : LogisticsEntry {
        override val id get() = stay.id
        override val date get() = stay.checkIn
        override val time: String? get() = null
    }
}

/** Chronological by date, then time; a missing time sorts after timed entries of the same date, a missing date last. */
fun logisticsEntries(snapshot: TripSnapshot): List<LogisticsEntry> {
    val all: List<LogisticsEntry> =
        snapshot.flights.map { LogisticsEntry.FlightEntry(it) } +
            snapshot.trains.map { LogisticsEntry.TrainEntry(it) } +
            snapshot.stays.map { LogisticsEntry.StayEntry(it, snapshot.places[it.placeId]?.name?.takeIf { n -> n.isNotBlank() } ?: "Unterkunft") }
    return all.sortedWith(
        compareBy<LogisticsEntry>({ it.date.ifBlank { "9999-99-99" } }, { it.time?.ifBlank { null } ?: "99:99" }, { it.id }),
    )
}

@Composable
fun LogisticsScreen(snapshot: TripSnapshot, modifier: Modifier = Modifier, attachments: AttachmentActions? = null) {
    val entries = remember(snapshot) { logisticsEntries(snapshot) }
    if (entries.isEmpty()) {
        Text("Keine Flüge, Züge oder Unterkünfte.", modifier.padding(16.dp).testTag("logistics-empty"))
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("logistics-list")) {
        items(entries, key = { it.id }) { e ->
            Column(Modifier.fillMaxWidth().padding(16.dp).testTag("logistics-${e.id}")) {
                when (e) {
                    is LogisticsEntry.FlightEntry -> FlightCard(e.flight)
                    is LogisticsEntry.TrainEntry -> TrainCard(e.train)
                    is LogisticsEntry.StayEntry -> StayCard(e.stay, e.name)
                }
                val type = when (e) {
                    is LogisticsEntry.FlightEntry -> EntityType.FLIGHT
                    is LogisticsEntry.TrainEntry -> EntityType.TRAIN
                    is LogisticsEntry.StayEntry -> EntityType.STAY
                }
                AttachmentsFor(snapshot.attachmentsFor(type, e.id), attachments, onAdd = { attachments?.onAdd?.invoke(type, e.id) })
                Text(
                    "Live-Status: –",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            HorizontalDivider()
        }
    }
}

private fun endpointText(e: Endpoint): String {
    val place = listOf(e.name.takeIf { it.isNotBlank() }, e.code?.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().joinToString(" ")
    val whenText = listOfNotNull(e.date?.takeIf { it.isNotBlank() }?.let(::dayLabel), e.time?.takeIf { it.isNotBlank() }).joinToString(" ")
    return listOf(place, whenText).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "–" }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Details(confirmation: String?, travelers: List<String>, note: String) {
    confirmation?.takeIf { it.isNotBlank() }?.let { Line("Bestätigungsnummer: $it") }
    if (travelers.isNotEmpty()) Line("Reisende: " + travelers.joinToString(", "))
    if (note.isNotBlank()) MarkdownText(note, Modifier.padding(top = 4.dp))
}

@Composable
private fun FlightCard(f: Flight) {
    Title("Flug " + listOfNotNull(f.airline ?: f.airlineCode, f.number).filter { it.isNotBlank() }.joinToString(" "))
    Line("Abflug: " + endpointText(f.depart))
    Line("Ankunft: " + endpointText(f.arrive))
    Details(f.confirmationNumber, f.travelerNames, f.note)
}

@Composable
private fun TrainCard(t: Train) {
    Title("Zug" + (t.carrier?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""))
    Line("Strecke: ${t.depart.name.ifBlank { "–" }} → ${t.arrive.name.ifBlank { "–" }}")
    Line("Abfahrt: " + endpointText(t.depart))
    Line("Ankunft: " + endpointText(t.arrive))
    Details(t.confirmationNumber, t.travelerNames, t.note)
}

@Composable
private fun StayCard(s: Stay, name: String) {
    Title(name)
    Line("Check-in: " + s.checkIn.ifBlank { "–" }.let(::dayLabel))
    Line("Check-out: " + s.checkOut.ifBlank { "–" }.let(::dayLabel))
    s.cancellationDeadline?.takeIf { it.isNotBlank() }?.let { Line("Stornofrist: " + dayLabel(it)) }
    Details(s.confirmationNumber, s.travelerNames, s.note)
}
