package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.RouteMap
import de.wanderlogplus.app.maps.routeFor
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.categorySymbol
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.client.ItemPosition
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.DayFlow
import de.wanderlogplus.shared.query.DayFlowView
import de.wanderlogplus.shared.query.FlowEntry
import de.wanderlogplus.shared.query.FlowLeg
import de.wanderlogplus.shared.query.isOpenAt
import de.wanderlogplus.shared.sync.EntityType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
private val TextCardShape = RoundedCornerShape(18.dp)

/**
 * Timeline of one day as its [flow]: last night's stay ("Start", with the check-out on its day), the program [items]
 * (marker = 1-based number; places as large photo cards with an "offen" badge when open at [now], notes as text
 * cards) and the day's rides at their place in it (marker = departure time, route map, ticket chips tagged
 * `ticket-<id>`), finally tonight's stay ("Nacht", with the check-in on its day). [legs] draws the way to an entry above
 * it. Without items [emptyProgram] stands in for the program.
 *
 * Tags: `<moveTagPrefix><i>`, `<itemTagPrefix><item id>`, `timeline-start`, `timeline-night`. [itemActions] (e.g. a ⋮
 * menu) is shown at the end of every program card.
 */
@Composable
fun DayTimeline(
    snapshot: TripSnapshot,
    items: List<DayItem>,
    flow: DayFlowView,
    accent: Color,
    /** Null makes the program cards non-clickable. */
    onOpenItem: ((DayItem) -> Unit)?,
    modifier: Modifier = Modifier,
    now: LocalDateTime? = null,
    onOpenAttachment: (Attachment) -> Unit = {},
    available: (hash: String) -> Boolean = { true },
    itemTagPrefix: String = "day-item-",
    moveTagPrefix: String = "movement-",
    itemActions: (@Composable (DayItem) -> Unit)? = null,
    emptyProgram: (@Composable () -> Unit)? = null,
    /** The way to an entry of the [flow], shown above it; null shows none. */
    legs: (@Composable (FlowLeg) -> Unit)? = null,
    /** A small "insert here" element above the first program item and below every item; null shows none. */
    insertSlot: (@Composable (ItemPosition) -> Unit)? = null,
) {
    val dow = now?.let { it.dayOfWeek.value % 7 } // 0 = Sunday
    val time = now?.format(HH_MM)
    fun stayName(st: Stay) = snapshot.places[st.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft"
    var rides = 0
    val shown = flow.entries.mapNotNull { (it as? FlowEntry.Item)?.item?.id }
    val emptyFirst = items.isEmpty() && emptyProgram != null
    Timeline(modifier, accent = accent) {
        flow.entries.forEachIndexed { idx, entry ->
            val leg: @Composable () -> Unit = { flow.legTo(idx)?.let { l -> legs?.invoke(l) } }
            // Without a program the hint stands after the start (or first), before rides and the night.
            if (emptyFirst && idx == (if (flow.entries.first() is FlowEntry.Start) 1 else 0)) {
                item("Programm", key = "empty") { emptyProgram!!() }
            }
            when (entry) {
                is FlowEntry.Start -> item("Start", key = "start") {
                    TextCard(
                        title = stayName(entry.stay),
                        subtitle = if (entry.checkOut) checkOutText(entry.stay) else "Unterkunft",
                        titleMaxLines = 2,
                        modifier = Modifier.testTag("timeline-start"),
                    )
                }
                is FlowEntry.Ride -> {
                    val i = rides++
                    item(entry.movement.time?.takeIf { it.isNotBlank() } ?: "Fahrt", key = "move-$i") {
                        Column {
                            leg()
                            MoveCard(snapshot, entry.movement, accent, onOpenAttachment, available, Modifier.testTag("$moveTagPrefix$i"))
                        }
                    }
                }
                is FlowEntry.Item -> {
                    val item = entry.item
                    val i = shown.indexOf(item.id)
                    // The time stands at the dot (as on "Heute"); without one the entry has no marker.
                    item(item.time?.takeIf { it.isNotBlank() }.orEmpty(), key = "item-${item.id}") {
                        Column {
                            if (i == 0) insertSlot?.invoke(ItemPosition.Start)
                            val tag = Modifier.testTag(itemTagPrefix + item.id)
                            val actions: (@Composable () -> Unit)? = itemActions?.let { a -> { a(item) } }
                            when (item.kind) {
                                DayItemKind.PLACE -> Column {
                                    leg()
                                    val place = item.placeId?.let { snapshot.places[it] }
                                    val saved = snapshot.savedPlaces.firstOrNull { it.placeId == item.placeId }
                                    val isStay = item.placeId != null && snapshot.stays.any { it.placeId == item.placeId }
                                    val open = dow != null && time != null && place != null && isOpenAt(place.openingPeriods, dow, time) == true
                                    PhotoCard(
                                        title = place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort",
                                        subtitle = listOfNotNull(
                                            if (isStay) "Unterkunft" else saved?.category?.let { categoryLabel(it) },
                                            entry.checkIn?.let { checkInText(it, item.time) },
                                            place?.rating?.takeIf { saved == null }?.let { ratingLabel(it) },
                                        ).joinToString(" · "),
                                        imageUrl = place?.heroImageUrl(),
                                        placeholderSymbol = if (isStay) "🏨" else categorySymbol(saved?.category),
                                        accent = accent,
                                        style = PhotoCardStyle.Compact,
                                        extra = saved?.let { s -> { de.wanderlogplus.app.ui.places.RatingStrip(place, s) } },
                                        badges = if (open) listOf(BadgeSpec("offen", BadgeTone.Positive)) else emptyList(),
                                        onClick = onOpenItem?.let { open -> { open(item) } },
                                        trailing = actions,
                                        modifier = tag,
                                    )
                                }
                                DayItemKind.NOTE -> TextCard(
                                    title = item.text.ifBlank { "Notiz" },
                                    subtitle = item.time?.takeIf { it.isNotBlank() },
                                    onClick = onOpenItem?.let { open -> { open(item) } },
                                    trailing = actions,
                                    modifier = tag,
                                )
                            }
                            insertSlot?.invoke(ItemPosition.After(item.id))
                        }
                    }
                }
                is FlowEntry.Night -> item("Nacht", key = "night") {
                    Column {
                        leg()
                        TextCard(
                            title = stayName(entry.stay),
                            subtitle = if (entry.checkIn) checkInText(entry.stay, null) + " · Übernachtung" else "Übernachtung",
                            titleMaxLines = 2,
                            modifier = Modifier.testTag("timeline-night"),
                        )
                    }
                }
            }
        }
        if (emptyFirst && flow.entries.size <= (if (flow.entries.firstOrNull() is FlowEntry.Start) 1 else 0)) {
            item("Programm", key = "empty") { emptyProgram!!() }
        }
    }
}

/** "Check-out bis 10:00", or just "Check-out". */
fun checkOutText(stay: Stay): String = stay.checkOutTime?.takeIf { it.isNotBlank() }?.let { "Check-out bis $it" } ?: "Check-out"

/** "Check-in ab 15:00" ("Check-in erst ab 15:00" when [planned] is earlier), or just "Check-in". */
fun checkInText(stay: Stay, planned: String?): String {
    val from = stay.checkInTime?.takeIf { it.isNotBlank() } ?: return "Check-in"
    val early = DayFlow.mins(planned)?.let { p -> DayFlow.mins(from)?.let { p < it } } == true
    return if (early) "Check-in erst ab $from" else "Check-in ab $from"
}

/** "Nara → Kyoto": endpoint names, falling back to codes. */
fun Movement.routeTitle(): String {
    fun e(name: String, code: String?) = name.takeIf { it.isNotBlank() } ?: code ?: "?"
    return when (this) {
        is Movement.FlightMove -> "${e(flight.depart.name, flight.depart.code)} → ${e(flight.arrive.name, flight.arrive.code)}"
        is Movement.TrainMove -> "${e(train.depart.name, train.depart.code)} → ${e(train.arrive.name, train.arrive.code)}"
    }
}

/** "über Frankfurt Hbf, Nagoya" for a leg with stopovers; null without. */
fun Movement.viaLine(): String? {
    val via = when (this) {
        is Movement.FlightMove -> flight.via
        is Movement.TrainMove -> train.via
    }
    val names = via.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } ?: it.code?.takeIf { c -> c.isNotBlank() } }
    return names.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "über ")
}

/**
 * "09:12 – 09:58 · Carrier" (flights: airline code or name plus number); missing parts are left out. An arrival on a
 * later date than the departure gets "+n" days ("11:15 – 06:15 +1").
 */
fun Movement.timesLine(): String {
    val (dep, arr, carrier) = when (this) {
        is Movement.FlightMove -> Triple(
            flight.depart, flight.arrive,
            listOfNotNull(flight.airlineCode ?: flight.airline, flight.number).filter { it.isNotBlank() }.joinToString(" "),
        )
        is Movement.TrainMove -> Triple(train.depart, train.arrive, train.carrier.orEmpty())
    }
    return listOf(timesOf(dep, arr), carrier.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** "09:12 – 09:58", with "+n" after the arrival when it is n days after the departure; empty without times. */
fun timesOf(depart: Endpoint, arrive: Endpoint): String {
    val arrTime = arrive.time?.takeIf { it.isNotBlank() }?.let { t ->
        val days = dayOffset(depart.date, arrive.date)
        if (days > 0) "$t +$days" else t
    }
    return listOfNotNull(depart.time?.takeIf { it.isNotBlank() }, arrTime).joinToString(" – ")
}

/** Whole days from [from] to [to] ("yyyy-MM-dd"); 0 when either is missing or unparsable. */
private fun dayOffset(from: String?, to: String?): Long {
    val a = from?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return 0
    val b = to?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return 0
    return ChronoUnit.DAYS.between(a, b)
}

@Composable
private fun MoveCard(
    snapshot: TripSnapshot,
    m: Movement,
    accent: Color,
    onOpenAttachment: (Attachment) -> Unit,
    available: (String) -> Boolean,
    modifier: Modifier,
) {
    val (type, id, route) = when (m) {
        is Movement.FlightMove -> Triple(EntityType.FLIGHT, m.flight.id, routeFor(m.flight, snapshot))
        is Movement.TrainMove -> Triple(EntityType.TRAIN, m.train.id, routeFor(m.train, snapshot))
    }
    val tickets = snapshot.attachmentsFor(type, id)
    Card(
        modifier.fillMaxWidth(),
        shape = TextCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        RouteMap(route, accent, height = 120.dp)
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                m.routeTitle(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            m.viaLine()?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("move-via"))
            }
            m.timesLine().takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (tickets.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tickets.forEach { a ->
                        AssistChip(
                            onClick = { onOpenAttachment(a) },
                            label = {
                                val name = a.fileName.ifBlank { "Anhang" }
                                Text(if (available(a.fileHash)) name else "$name (nicht offline)", maxLines = 1)
                            },
                            leadingIcon = { Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("ticket-${a.id}"),
                        )
                    }
                }
            }
        }
    }
}

/** Card without photo for notes and the night's stay. */
@Composable
private fun TextCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Notes may run longer; names (the night's stay) keep the usual two lines. */
    titleMaxLines: Int = 4,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.size(4.dp))
                trailing()
            }
        }
    }
    val m = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(onClick = onClick, modifier = m, shape = TextCardShape, colors = colors, elevation = elevation) { body() }
    } else {
        Card(modifier = m, shape = TextCardShape, colors = colors, elevation = elevation) { body() }
    }
}
