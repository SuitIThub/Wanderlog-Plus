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
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.isOpenAt
import de.wanderlogplus.shared.sync.EntityType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")
private val TextCardShape = RoundedCornerShape(18.dp)

/**
 * Timeline of one day: [movements] first (marker = departure time, route map, ticket chips tagged `ticket-<id>`),
 * then the program [items] (marker = 1-based number; places as large photo cards with an "offen" badge when open at
 * [now], notes as text cards), finally the [stay] as "Nacht". Without items [emptyProgram] stands in for the program.
 *
 * Tags: `movement-<i>`, `<itemTagPrefix><item id>`, `timeline-night`. [itemActions] (e.g. a ⋮ menu) is shown at the end
 * of every program card.
 */
@Composable
fun DayTimeline(
    snapshot: TripSnapshot,
    items: List<DayItem>,
    movements: List<Movement>,
    stay: Stay?,
    accent: Color,
    onOpenItem: (DayItem) -> Unit,
    modifier: Modifier = Modifier,
    now: LocalDateTime? = null,
    onOpenAttachment: (Attachment) -> Unit = {},
    available: (hash: String) -> Boolean = { true },
    itemTagPrefix: String = "day-item-",
    itemActions: (@Composable (DayItem) -> Unit)? = null,
    emptyProgram: (@Composable () -> Unit)? = null,
) {
    val dow = now?.let { it.dayOfWeek.value % 7 } // 0 = Sunday
    val time = now?.format(HH_MM)
    Timeline(modifier, accent = accent) {
        movements.forEachIndexed { i, m ->
            item(m.time?.takeIf { it.isNotBlank() } ?: "Fahrt", key = "move-$i") {
                MoveCard(snapshot, m, accent, onOpenAttachment, available, Modifier.testTag("movement-$i"))
            }
        }
        if (items.isEmpty() && emptyProgram != null) {
            item("Programm", key = "empty") { emptyProgram() }
        }
        items.forEachIndexed { i, item ->
            item((i + 1).toString(), key = item.id) {
                val tag = Modifier.testTag(itemTagPrefix + item.id)
                val actions: (@Composable () -> Unit)? = itemActions?.let { a -> { a(item) } }
                when (item.kind) {
                    DayItemKind.PLACE -> {
                        val place = item.placeId?.let { snapshot.places[it] }
                        val saved = snapshot.savedPlaces.firstOrNull { it.placeId == item.placeId }
                        val open = dow != null && time != null && place != null && isOpenAt(place.openingPeriods, dow, time) == true
                        PhotoCard(
                            title = place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort",
                            subtitle = listOfNotNull(
                                item.time?.takeIf { it.isNotBlank() },
                                saved?.category?.let { categoryLabel(it) },
                                place?.rating?.let { ratingLabel(it) },
                            ).joinToString(" · "),
                            imageUrl = place?.heroImageUrl(),
                            placeholderSymbol = categorySymbol(saved?.category),
                            accent = accent,
                            style = PhotoCardStyle.Large,
                            badges = if (open) listOf(BadgeSpec("offen", BadgeTone.Positive)) else emptyList(),
                            onClick = { onOpenItem(item) },
                            trailing = actions,
                            modifier = tag,
                        )
                    }
                    DayItemKind.NOTE -> TextCard(
                        title = item.text.ifBlank { "Notiz" },
                        subtitle = item.time?.takeIf { it.isNotBlank() },
                        onClick = { onOpenItem(item) },
                        trailing = actions,
                        modifier = tag,
                    )
                }
            }
        }
        if (stay != null) {
            item("Nacht", key = "night") {
                TextCard(
                    title = snapshot.places[stay.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft",
                    subtitle = "Übernachtung",
                    modifier = Modifier.testTag("timeline-night"),
                )
            }
        }
    }
}

/** "Nara → Kyoto": endpoint names, falling back to codes. */
fun Movement.routeTitle(): String {
    fun e(name: String, code: String?) = name.takeIf { it.isNotBlank() } ?: code ?: "?"
    return when (this) {
        is Movement.FlightMove -> "${e(flight.depart.name, flight.depart.code)} → ${e(flight.arrive.name, flight.arrive.code)}"
        is Movement.TrainMove -> "${e(train.depart.name, train.depart.code)} → ${e(train.arrive.name, train.arrive.code)}"
    }
}

/** "09:12 – 09:58 · Carrier" (flights: airline code or name plus number); missing parts are left out. */
fun Movement.timesLine(): String {
    val (dep, arr, carrier) = when (this) {
        is Movement.FlightMove -> Triple(
            flight.depart.time, flight.arrive.time,
            listOfNotNull(flight.airlineCode ?: flight.airline, flight.number).filter { it.isNotBlank() }.joinToString(" "),
        )
        is Movement.TrainMove -> Triple(train.depart.time, train.arrive.time, train.carrier.orEmpty())
    }
    val times = listOfNotNull(dep, arr).filter { it.isNotBlank() }.joinToString(" – ")
    return listOf(times, carrier.trim()).filter { it.isNotEmpty() }.joinToString(" · ")
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
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
