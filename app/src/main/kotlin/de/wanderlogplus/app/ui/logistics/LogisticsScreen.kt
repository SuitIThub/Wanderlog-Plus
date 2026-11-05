package de.wanderlogplus.app.ui.logistics

import de.wanderlogplus.app.ui.design.heroImage
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.RouteMap
import de.wanderlogplus.app.maps.routeFor
import de.wanderlogplus.app.ui.dayCardLabel
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.app.ui.design.PhotoCardStyle
import de.wanderlogplus.app.ui.design.ScreenTab
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.design.routeTitle
import de.wanderlogplus.app.ui.design.viaLine
import de.wanderlogplus.app.ui.design.timesOf
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.sync.EntityType
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** Booking detail below the Logistik tab; [type] is "flight", "train" or "stay". */
const val LOGISTICS_DETAIL_ROUTE = "logistik/{type}/{id}"

fun logisticsDetailRoute(type: EntityType, id: String) = "logistik/${type.name.lowercase()}/${android.net.Uri.encode(id)}"

/** Path segment of [LOGISTICS_DETAIL_ROUTE] back to the entity type; null for anything but flight, train and stay. */
fun logisticsTypeOf(segment: String?): EntityType? =
    listOf(EntityType.FLIGHT, EntityType.TRAIN, EntityType.STAY).firstOrNull { it.name.equals(segment, ignoreCase = true) }

/** One row of the Logistik tab. [date]/[time] are the sort key (depart / check-in). */
sealed interface LogisticsEntry {
    val id: String
    val date: String
    val time: String?
    val type: EntityType

    data class FlightEntry(val flight: Flight) : LogisticsEntry {
        override val id get() = flight.id
        override val date get() = flight.depart.date.orEmpty()
        override val time get() = flight.depart.time
        override val type get() = EntityType.FLIGHT
    }

    data class TrainEntry(val train: Train) : LogisticsEntry {
        override val id get() = train.id
        override val date get() = train.depart.date.orEmpty()
        override val time get() = train.depart.time
        override val type get() = EntityType.TRAIN
    }

    data class StayEntry(val stay: Stay, val name: String) : LogisticsEntry {
        override val id get() = stay.id
        override val date get() = stay.checkIn
        override val time: String? get() = null
        override val type get() = EntityType.STAY
    }
}

internal fun stayName(snapshot: TripSnapshot, stay: Stay): String =
    snapshot.places[stay.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft"

/** Chronological by date, then time; a missing time sorts after timed entries of the same date, a missing date last. */
fun logisticsEntries(snapshot: TripSnapshot): List<LogisticsEntry> {
    val all: List<LogisticsEntry> =
        snapshot.flights.map { LogisticsEntry.FlightEntry(it) } +
            snapshot.trains.map { LogisticsEntry.TrainEntry(it) } +
            snapshot.stays.map { LogisticsEntry.StayEntry(it, stayName(snapshot, it)) }
    return all.sortedWith(
        compareBy<LogisticsEntry>({ it.date.ifBlank { "9999-99-99" } }, { it.time?.ifBlank { null } ?: "99:99" }, { it.id }),
    )
}

/** Long title with the airline name, used by the share targets ("Flug Lufthansa 716"). */
fun flightTitle(f: Flight): String = "Flug " + listOfNotNull(f.airline ?: f.airlineCode, f.number).filter { it.isNotBlank() }.joinToString(" ")

/** Short title for cards and the detail hero: "Flug LH 716" (code preferred, not repeated when the number carries it). */
fun flightCardTitle(f: Flight): String {
    val carrier = (f.airlineCode ?: f.airline)?.trim()?.takeIf { it.isNotEmpty() }
    val number = f.number?.trim()?.takeIf { it.isNotEmpty() }
    val parts = when {
        carrier != null && number != null && number.startsWith(carrier) -> listOf(number)
        else -> listOfNotNull(carrier, number)
    }
    return (listOf("Flug") + parts).joinToString(" ")
}

fun trainTitle(t: Train): String = "Zug" + (t.carrier?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

private fun parseDate(s: String?): LocalDate? =
    s?.takeIf { it.isNotBlank() }?.let { try { LocalDate.parse(it.trim()) } catch (_: DateTimeParseException) { null } }

/** "2027-05-01" -> "01.05."; unparsable input is returned unchanged. */
internal fun shortDate(iso: String): String = parseDate(iso)?.let { "%02d.%02d.".format(it.dayOfMonth, it.monthValue) } ?: iso

/** "3 Nächte · 17.–20.05." (or "30.04.–02.05." across months); "ab …"/"bis …" with one date; null without dates. */
fun stayPeriod(s: Stay): String? {
    val from = parseDate(s.checkIn)
    val to = parseDate(s.checkOut)
    return when {
        from != null && to != null -> {
            val range = if (from.year == to.year && from.month == to.month) {
                "%02d.–%02d.%02d.".format(from.dayOfMonth, to.dayOfMonth, to.monthValue)
            } else {
                shortDate(s.checkIn) + "–" + shortDate(s.checkOut)
            }
            val nights = ChronoUnit.DAYS.between(from, to)
            when {
                nights == 1L -> "1 Nacht · $range"
                nights > 1L -> "$nights Nächte · $range"
                else -> range
            }
        }
        from != null -> "ab " + shortDate(s.checkIn)
        to != null -> "bis " + shortDate(s.checkOut)
        else -> null
    }
}

/** True when the stay has a cancellation deadline that has not passed yet ([today] null = unknown, counts as open). */
internal fun cancellationOpen(s: Stay, today: String?): Boolean {
    val deadline = parseDate(s.cancellationDeadline) ?: return false
    val now = parseDate(today) ?: return true
    return !deadline.isBefore(now)
}

/** "10:00 – 20:00" ("+1" after an arrival on the next day); empty without times. Same helper as the timeline. */
internal fun timesText(depart: Endpoint, arrive: Endpoint): String = timesOf(depart, arrive)

private const val TAB_MOVES = 0
private const val TAB_STAYS = 1

/**
 * Logistik: tabs Fahrten (flights and trains grouped by departure date, each with a route map strip) and Unterkünfte
 * (large photo cards with period and an open cancellation deadline). Booking data (confirmation number, travelers)
 * is never shown here; a tap opens the detail page via [onOpen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogisticsScreen(
    snapshot: TripSnapshot,
    modifier: Modifier = Modifier,
    onOpen: (LogisticsEntry) -> Unit = {},
    /** ISO date (trip zone) deciding whether a cancellation deadline still lies ahead; null shows every deadline. */
    today: String? = null,
    /** "Hinzufügen" for a train, flight or stay; null hides the button. */
    onAdd: ((EntityType) -> Unit)? = null,
) {
    val entries = remember(snapshot) { logisticsEntries(snapshot) }
    val moves = remember(entries) { entries.filter { it !is LogisticsEntry.StayEntry } }
    val stays = remember(entries) { entries.filterIsInstance<LogisticsEntry.StayEntry>() }
    var tab by rememberSaveable { mutableIntStateOf(TAB_MOVES) }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            ScreenTab("Fahrten", tab == TAB_MOVES, "tab-moves") { tab = TAB_MOVES }
            ScreenTab("Unterkünfte", tab == TAB_STAYS, "tab-stays") { tab = TAB_STAYS }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == TAB_MOVES) MovesTab(snapshot, moves, onOpen) else StaysTab(snapshot, stays, onOpen, today)
            if (onAdd != null) {
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    androidx.compose.material3.ExtendedFloatingActionButton(
                        onClick = { if (tab == TAB_MOVES) menu = true else onAdd(EntityType.STAY) },
                        icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Add, contentDescription = null) },
                        text = { Text("Hinzufügen") },
                        modifier = Modifier.testTag("logistics-add"),
                    )
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Zug / ÖPNV") }, onClick = { menu = false; onAdd(EntityType.TRAIN) },
                            leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Train, contentDescription = null) },
                            modifier = Modifier.testTag("logistics-add-train"),
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Flug") }, onClick = { menu = false; onAdd(EntityType.FLIGHT) },
                            leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Flight, contentDescription = null) },
                            modifier = Modifier.testTag("logistics-add-flight"),
                        )
                    }
                }
            }
        }
    }
}

private val ListPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)

@Composable
private fun MovesTab(snapshot: TripSnapshot, moves: List<LogisticsEntry>, onOpen: (LogisticsEntry) -> Unit) {
    if (moves.isEmpty()) {
        EmptyHint("Keine Fahrten", Modifier.padding(16.dp).testTag("logistics-empty"))
        return
    }
    val groups = remember(moves) { moves.groupBy { it.date } }
    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize().testTag("logistics-list"), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { (date, group) ->
            item(key = "date-$date") {
                Text(
                    if (date.isBlank()) "Ohne Datum" else dayCardLabel(date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp).testTag("logistics-date-$date"),
                )
            }
            items(group, key = { "${it.type}-${it.id}" }) { e -> MoveCard(snapshot, e, accent) { onOpen(e) } }
        }
    }
}

@Composable
private fun MoveCard(snapshot: TripSnapshot, e: LogisticsEntry, accent: Color, onClick: () -> Unit) {
    val (title, movement, route) = when (e) {
        is LogisticsEntry.FlightEntry -> Triple(flightCardTitle(e.flight), Movement.FlightMove(e.flight), routeFor(e.flight, snapshot))
        is LogisticsEntry.TrainEntry -> Triple(trainTitle(e.train), Movement.TrainMove(e.train), routeFor(e.train, snapshot))
        is LogisticsEntry.StayEntry -> return
    }
    val times = when (movement) {
        is Movement.FlightMove -> timesText(movement.flight.depart, movement.flight.arrive)
        is Movement.TrainMove -> timesText(movement.train.depart, movement.train.arrive)
    }
    val files = snapshot.attachmentsFor(e.type, e.id).size
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("logistics-${e.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        RouteMap(route, accent, height = 100.dp)
        Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    movement.routeTitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                movement.viaLine()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("move-via"))
                }
                if (times.isNotEmpty()) {
                    Text(times, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            if (files > 0) Badge(BadgeSpec("📎 $files", BadgeTone.Neutral), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun StaysTab(snapshot: TripSnapshot, stays: List<LogisticsEntry.StayEntry>, onOpen: (LogisticsEntry) -> Unit, today: String?) {
    if (stays.isEmpty()) {
        EmptyHint("Keine Unterkünfte", Modifier.padding(16.dp).testTag("logistics-empty"))
        return
    }
    val fallback = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize().testTag("logistics-list"), contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(stays, key = { "${it.type}-${it.id}" }) { e ->
            val s = e.stay
            val region = s.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
            val files = snapshot.attachmentsFor(EntityType.STAY, s.id).size
            val badges = listOfNotNull(
                s.cancellationDeadline?.takeIf { cancellationOpen(s, today) }?.let { BadgeSpec("Storno bis " + shortDate(it), BadgeTone.Warning) },
                files.takeIf { it > 0 }?.let { BadgeSpec("📎 $it", BadgeTone.Neutral) },
            )
            PhotoCard(
                title = e.name,
                subtitle = stayPeriod(s),
                imageUrl = s.heroImage(snapshot.places[s.placeId]),
                placeholderSymbol = "🏨",
                accent = region?.let { regionColor(it.color) } ?: fallback,
                style = PhotoCardStyle.Large,
                badges = badges,
                onClick = { onOpen(e) },
                modifier = Modifier.testTag("logistics-${e.id}"),
            )
        }
    }
}

/** "Frankfurt (FRA) · Sa, 08.05. 10:00"; "–" when empty. */
internal fun endpointText(e: Endpoint): String {
    val place = listOf(e.name.takeIf { it.isNotBlank() }, e.code?.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().joinToString(" ")
    val whenText = listOfNotNull(e.date?.takeIf { it.isNotBlank() }?.let(::dayLabel), e.time?.takeIf { it.isNotBlank() }).joinToString(" ")
    return listOf(place, whenText).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "–" }
}
