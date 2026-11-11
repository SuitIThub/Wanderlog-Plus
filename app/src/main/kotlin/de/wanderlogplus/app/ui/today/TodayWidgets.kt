package de.wanderlogplus.app.ui.today

import de.wanderlogplus.shared.query.DeadlineKind
import de.wanderlogplus.shared.query.DeadlineState
import de.wanderlogplus.shared.query.DeadlineTarget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.GoogleExternalLinks
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.routeTitle
import de.wanderlogplus.app.ui.design.timesLine
import de.wanderlogplus.app.ui.logistics.platformText
import de.wanderlogplus.app.ui.logistics.shortLine
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.query.DayNow
import de.wanderlogplus.shared.query.DayNowView
import de.wanderlogplus.shared.query.DayStep
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.Reservations
import de.wanderlogplus.shared.query.StepState
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** A dashboard card: small title row (with an optional action) above the content. */
@Composable
internal fun Widget(
    title: String,
    tag: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    action: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                action?.invoke()
            }
            content()
        }
    }
    val shape = RoundedCornerShape(18.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = scheme.surfaceContainerLow, tonalElevation = 1.dp, modifier = modifier.fillMaxWidth().testTag(tag)) { body() }
    } else {
        Surface(shape = shape, color = scheme.surfaceContainerLow, tonalElevation = 1.dp, modifier = modifier.fillMaxWidth().testTag(tag)) { body() }
    }
}

private fun minutesOf(now: LocalDateTime) = now.hour * 60 + now.minute

private fun dowOf(date: String): Int = runCatching { LocalDate.parse(date).dayOfWeek.value % 7 }.getOrDefault(0)

/** "13 Min. zu Fuß" for a way to a step. */
private fun wayText(minutes: Int?, mode: TravelMode?): String? = minutes?.let { m ->
    "$m Min. " + when (mode) {
        TravelMode.WALK -> "zu Fuß"
        TravelMode.DRIVE -> "Auto"
        TravelMode.BICYCLE -> "Rad"
        else -> "ÖPNV"
    }
}

private fun wayIcon(mode: TravelMode?): ImageVector = when (mode) {
    TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.BICYCLE -> Icons.Filled.PedalBike
    else -> Icons.Filled.DirectionsTransit
}

private fun Movement.icon(): ImageVector = if (this is Movement.FlightMove) Icons.Filled.Flight else Icons.Filled.Train

private fun Movement.targetType() = if (this is Movement.FlightMove) EntityType.FLIGHT else EntityType.TRAIN

private fun Movement.id() = when (this) {
    is Movement.FlightMove -> flight.id
    is Movement.TrainMove -> train.id
}

/** "Gleis 14" of a train with a chosen connection. */
private fun Movement.platform(): String? = (this as? Movement.TrainMove)?.train?.platformText()

private fun Movement.label(): String = when (this) {
    is Movement.FlightMove -> "Flug"
    is Movement.TrainMove -> "Zug"
}

private fun stepName(s: DayStep): String = when (s) {
    is DayStep.Visit -> s.place?.name?.takeIf { it.isNotBlank() } ?: "Ort"
    is DayStep.Move -> s.movement.label() + " " + s.movement.routeTitle()
    is DayStep.Note -> s.item.text.lineSequence().firstOrNull().orEmpty().ifBlank { "Notiz" }
}

/** "in 2 Std. 10 Min." / "in 25 Min." / "jetzt". */
internal fun inText(minutes: Long): String = when {
    minutes <= 0 -> "jetzt"
    minutes < 60 -> "in $minutes Min."
    minutes % 60 == 0L -> "in ${minutes / 60} Std."
    else -> "in ${minutes / 60} Std. ${minutes % 60} Min."
}

@Composable
private fun openColor(text: String): Color = when {
    text.startsWith("offen") -> Color(0xFF2E7D32)
    text.startsWith("schließt") -> Color(0xFFE08A00)
    else -> MaterialTheme.colorScheme.error
}

/**
 * "Jetzt & Nächstes": a big card for what happens now (or next) with opening state, reservation note and a route
 * button, the step after it with the way and when to leave; below, the whole day as compact rows ending at the stay.
 */
@Composable
internal fun NowNextWidget(
    snapshot: TripSnapshot,
    date: String,
    view: DayNowView,
    now: LocalDateTime,
    stay: Place?,
    accent: Color,
    onOpenDay: (String) -> Unit,
    onOpenLink: ((ExternalLink) -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenAttachment: (Attachment) -> Unit = {},
    available: (String) -> Boolean = { true },
) {
    val nowMin = minutesOf(now)
    val dow = dowOf(date)
    Widget("Tagesablauf", "today-program", modifier, accent) {
        if (view.steps.isEmpty()) {
            EmptyHint("Für heute ist noch nichts geplant")
            FilledTonalButton(onClick = { onOpenDay(date) }) { Text("Tag öffnen") }
            return@Widget
        }
        val focus = view.current ?: view.next
        val scheme = MaterialTheme.colorScheme
        Surface(shape = RoundedCornerShape(14.dp), color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth().testTag("now-card")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (focus == null) {
                    Text("Tag geschafft", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(stay?.name?.let { "Zurück zur Unterkunft: $it" } ?: "Alles erledigt für heute.", style = MaterialTheme.typography.titleMedium)
                } else {
                    val isNow = focus === view.current
                    val head = if (isNow) {
                        "JETZT" + (focus.end?.let { " · bis ca. ${DayNow.hhmm(it)}" } ?: "")
                    } else {
                        "ALS NÄCHSTES" + (focus.start?.let { " · " + (if (focus is DayStep.Visit && focus.estimated) "ca. " else "") + DayNow.hhmm(it) } ?: "") +
                            (view.leaveIn(nowMin)?.takeIf { it in 0..180 }?.let { " · los ${inText(it.toLong())}" } ?: "")
                    }
                    Text(head, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("now-head"))
                    FocusStep(snapshot, focus, dow, nowMin, onOpenLink, onOpenAttachment, available)
                    val after = if (isNow) view.next else null
                    if (after != null) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.onSecondaryContainer.copy(alpha = 0.2f))
                        val parts = listOfNotNull(
                            after.start?.let { (if (after is DayStep.Visit && after.estimated) "ca. " else "") + DayNow.hhmm(it) },
                            (after as? DayStep.Visit)?.let { wayText(it.wayMinutes, it.wayMode) },
                            view.leaveIn(nowMin)?.takeIf { it in 0..180 }?.let { "los ${inText(it.toLong())}" },
                        )
                        Text(
                            "Danach: ${stepName(after)}" + parts.joinToString(" · ", prefix = " · ").takeIf { parts.isNotEmpty() }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("now-after"),
                        )
                    }
                }
            }
        }
        Column {
            var moveIndex = 0
            val open = Modifier.clickable { onOpenDay(date) }
            // What the card above shows (now and next) is not repeated in the list.
            val inCard = setOfNotNull(focus, if (focus != null && focus === view.current) view.next else null)
            view.steps.forEach { s ->
                if (s in inCard) {
                    if (s is DayStep.Move) moveIndex++ // keep the tags of the other rides stable
                    return@forEach
                }
                when (s) {
                    is DayStep.Move -> StepRow(s, dow, nowMin, open.testTag("movement-${moveIndex++}"))
                    is DayStep.Visit -> StepRow(s, dow, nowMin, open.testTag("today-item-${s.item.id}"))
                    is DayStep.Note -> StepRow(s, dow, nowMin, open.testTag("today-item-${s.item.id}"))
                }
            }
            if (stay != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("timeline-night"), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(52.dp))
                    Icon(Icons.Filled.Home, contentDescription = "Nacht", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    val last = view.steps.lastOrNull { it is DayStep.Visit } as? DayStep.Visit
                    val dist = last?.place?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) && !(stay.lat == 0.0 && stay.lng == 0.0) }
                        ?.let { p -> DayNow.km(p.lat, p.lng, stay.lat, stay.lng) }
                    Text(
                        "Nacht: ${stay.name.ifBlank { "Unterkunft" }}" + (dist?.let { " · " + kmText(it) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun kmText(km: Double): String = if (km < 1) "${(km * 1000).toInt() / 10 * 10} m" else String.format(java.util.Locale.GERMAN, "%.1f km", km)

@Composable
private fun FocusStep(
    snapshot: TripSnapshot,
    s: DayStep,
    dow: Int,
    nowMin: Int,
    onOpenLink: ((ExternalLink) -> Unit)?,
    onOpenAttachment: (Attachment) -> Unit,
    available: (String) -> Boolean,
) {
    when (s) {
        is DayStep.Visit -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stepName(s), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val nav = s.place?.takeIf { onOpenLink != null }?.let { p -> GoogleExternalLinks.linksFor(p, s.saved?.category).firstOrNull { it.kind == LinkKind.NAVIGATE } }
                if (nav != null && onOpenLink != null) {
                    FilledTonalButton(onClick = { onOpenLink(nav) }, modifier = Modifier.testTag("now-route")) {
                        Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Route")
                    }
                }
            }
            val open = s.place?.let { DayNow.openText(it.openingPeriods, dow, nowMin) }
            val facts = listOfNotNull(s.saved?.let { categoryLabel(it.category) }, s.place?.rating?.let { ratingLabel(it) }).joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (open != null) Text(open, color = openColor(open), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("now-open"))
                if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val needs = s.saved?.takeIf { !it.reserved }?.let { Reservations.reason(it) }
            val tickets = s.saved?.let { snapshot.attachmentsFor(EntityType.SAVED_PLACE, it.id) }.orEmpty()
            if (needs != null || tickets.isNotEmpty() || s.wayMinutes != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    wayText(s.wayMinutes, s.wayMode)?.let { MiniFact(wayIcon(s.wayMode), "Weg $it") }
                    if (tickets.isNotEmpty()) MiniFact(Icons.Filled.ConfirmationNumber, if (tickets.size == 1) "1 Ticket" else "${tickets.size} Tickets")
                    if (needs != null) MiniFact(Icons.Filled.Warning, "Reservierung offen", MaterialTheme.colorScheme.error, Modifier.testTag("now-reservation"))
                }
            }
        }
        is DayStep.Move -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(s.movement.icon(), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(s.movement.routeTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(s.movement.timesLine() + (s.movement.platform()?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            TicketChips(snapshot.attachmentsFor(s.movement.targetType(), s.movement.id()), onOpenAttachment, available)
            val replace = LocalReplaceTrain.current
            (s.movement as? Movement.TrainMove)?.train?.let { t ->
                if (replace != null) androidx.compose.material3.TextButton(onClick = { replace(t.id) }, modifier = Modifier.testTag("today-replace")) { Text("Verpasst? Spätere Fahrt") }
            }
        }
        is DayStep.Note -> Text(stepName(s), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MiniFact(icon: ImageVector, text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (color == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else color)
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** One line of the day: time, state, name and small facts (way, opening, ticket, reservation). */
@Composable
private fun StepRow(s: DayStep, dow: Int, nowMin: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val done = s.state == StepState.DONE
    val faded = if (done) scheme.onSurfaceVariant.copy(alpha = 0.7f) else scheme.onSurface
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        val time = s.start?.let { (if (s is DayStep.Visit && s.estimated) "~" else "") + DayNow.hhmm(it) }.orEmpty()
        Text(time, Modifier.width(52.dp), style = MaterialTheme.typography.labelLarge, color = if (s.state == StepState.CURRENT) scheme.primary else scheme.onSurfaceVariant)
        val icon = when {
            s is DayStep.Move -> s.movement.icon()
            s is DayStep.Note -> Icons.AutoMirrored.Filled.Notes
            done -> Icons.Filled.CheckCircle
            s.state == StepState.CURRENT -> Icons.Filled.RadioButtonChecked
            else -> Icons.Filled.RadioButtonUnchecked
        }
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (s.state == StepState.CURRENT) scheme.primary else scheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            // A note reads as a quiet remark to the plan, not as a stop of its own.
            val note = s is DayStep.Note
            Text(
                stepName(s), style = if (note) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (note) scheme.onSurfaceVariant else faded, maxLines = if (note) 2 else 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (!note && (s.state == StepState.CURRENT || s.state == StepState.NEXT)) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (note) androidx.compose.ui.text.font.FontStyle.Italic else null,
            )
            val facts = when (s) {
                is DayStep.Move -> listOf(s.movement.timesLine())
                is DayStep.Visit -> listOfNotNull(
                    wayText(s.wayMinutes, s.wayMode)?.takeIf { !done },
                    s.place?.takeIf { !done }?.let { DayNow.openText(it.openingPeriods, dow, nowMin) }?.takeIf { it.startsWith("offen") || it.contains("geschlossen") },
                )
                is DayStep.Note -> emptyList()
            }.filter { it.isNotBlank() }
            if (facts.isNotEmpty()) {
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (s is DayStep.Visit && !done && s.saved?.let { !it.reserved && Reservations.needsReservation(it) } == true) {
            Icon(Icons.Filled.Warning, contentDescription = "Reservierung offen", tint = scheme.error, modifier = Modifier.size(16.dp))
        }
    }
}

/** A departure (or one under way) in the next [hours] hours, with its departure and arrival time. */
internal data class UpcomingMove(val movement: Movement, val departs: LocalDateTime, val arrives: LocalDateTime?)

internal fun upcomingMove(snapshot: TripSnapshot, now: LocalDateTime, hours: Long = 36): UpcomingMove? {
    fun at(date: String?, time: String?): LocalDateTime? {
        val d = date?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val m = DayNow.mins(time) ?: return null
        return d.atStartOfDay().plusMinutes(m.toLong())
    }
    val all = snapshot.flights.map { Movement.FlightMove(it) to (at(it.depart.date, it.depart.time) to at(it.arrive.date ?: it.depart.date, it.arrive.time)) } +
        snapshot.trains.map { Movement.TrainMove(it) to (at(it.depart.date, it.depart.time) to at(it.arrive.date ?: it.depart.date, it.arrive.time)) }
    return all.mapNotNull { (m, t) -> t.first?.let { UpcomingMove(m, it, t.second?.takeIf { a -> a >= it }) } }
        .filter { u -> (u.arrives ?: u.departs) >= now && u.departs <= now.plusHours(hours) }
        .minByOrNull { it.departs }
}

/** "Nächste Fahrt" / "Unterwegs": countdown, route, times and the tickets. */
@Composable
internal fun NextTripWidget(
    snapshot: TripSnapshot,
    move: UpcomingMove,
    now: LocalDateTime,
    accent: Color,
    onOpenAttachment: (Attachment) -> Unit,
    available: (String) -> Boolean,
    modifier: Modifier = Modifier,
    /** "Verpasst? Spätere Fahrt" for a train; null hides it. */
    onReplaceTrain: ((String) -> Unit)? = null,
) {
    val underway = move.departs <= now
    val minutes = ChronoUnit.MINUTES.between(now, move.departs)
    Widget(if (underway) "Unterwegs" else "Nächste Fahrt", "next-trip", modifier, accent, action = {
        Text(
            if (underway) move.arrives?.let { "an ${it.toLocalTime().toString().take(5)}" }.orEmpty() else inText(minutes),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (!underway && minutes <= 60) MaterialTheme.colorScheme.error else accent,
            modifier = Modifier.testTag("next-trip-countdown"),
        )
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(move.movement.icon(), contentDescription = move.movement.label(), modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(move.movement.routeTitle(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val day = move.departs.toLocalDate()
                val prefix = when (ChronoUnit.DAYS.between(now.toLocalDate(), day)) {
                    0L -> ""
                    1L -> "morgen · "
                    else -> dayLabel(day.toString()) + " · "
                }
                Text(prefix + move.movement.timesLine(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                move.movement.platform()?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("next-trip-platform")) }
                when (val m = move.movement) {
                    is Movement.TrainMove -> de.wanderlogplus.shared.seatmap.seatLine(m.train.travelClass, m.train.seats)
                    is Movement.FlightMove -> de.wanderlogplus.shared.seatmap.seatLine(m.flight.travelClass, m.flight.seats)
                }?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("next-trip-seats")) }
                (move.movement as? Movement.TrainMove)?.let { tm ->
                    if (de.wanderlogplus.app.ui.logistics.LocalTrainLive.current != null) {
                        de.wanderlogplus.app.ui.logistics.rememberTrainLive(tm.train.id)?.shortLine()?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("next-trip-live"))
                        }
                    }
                }
                (move.movement as? Movement.FlightMove)?.let { fm ->
                    if (de.wanderlogplus.app.ui.logistics.LocalFlightLive.current != null) {
                        de.wanderlogplus.app.ui.logistics.rememberFlightLive(fm.flight.id)?.shortLine()?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("next-trip-live"))
                        }
                    }
                }
            }
        }
        TicketChips(snapshot.attachmentsFor(move.movement.targetType(), move.movement.id()), onOpenAttachment, available)
        val train = (move.movement as? Movement.TrainMove)?.train
        if (train != null && onReplaceTrain != null) {
            androidx.compose.material3.TextButton(onClick = { onReplaceTrain(train.id) }, modifier = Modifier.testTag("today-replace")) { Text("Verpasst? Spätere Fahrt") }
        }
        // Part of a journey: what comes next and the change.
        val journey = androidx.compose.runtime.remember(snapshot, move) { de.wanderlogplus.client.journeyOf(snapshot, move.movement.targetType(), move.movement.id()) }
        journey?.let { j ->
            val i = j.indexOf(move.movement.targetType(), move.movement.id())
            j.legs.getOrNull(i + 1)?.let { next ->
                val t = de.wanderlogplus.client.transfer(snapshot, j.legs[i], next)
                Text(
                    "Danach: " + de.wanderlogplus.app.ui.logistics.legTitle(next) + (next.depart.time?.let { " um $it" } ?: "") +
                        (t.minutes?.let { " · Umstieg " + de.wanderlogplus.client.durationText(it) + if (t.tight) " (knapp)" else "" } ?: "") +
                        (listOfNotNull(t.fromSpot, t.toSpot).takeIf { it.size == 2 }?.let { " · " + it.joinToString(" → ") } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t.tight || t.overlaps) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("next-trip-after"),
                )
            }
        }
    }
}

/** Tickets of a train or flight as chips (tags `ticket-<id>`); files not stored locally are marked. */
@Composable
private fun TicketChips(tickets: List<Attachment>, onOpenAttachment: (Attachment) -> Unit, available: (String) -> Boolean) {
    if (tickets.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tickets.forEach { a ->
            AssistChip(
                onClick = { onOpenAttachment(a) },
                label = { Text(if (available(a.fileHash)) a.fileName else "${a.fileName} (nicht offline)", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.testTag("ticket-${a.id}"),
            )
        }
    }
}

/** One deadline row; [targetType]/[targetId] say what a tap opens ([savedPlaceId] is set for places). */
internal data class DeadlineRow(
    val date: String,
    val title: String,
    val text: String,
    val tag: String,
    val savedPlaceId: String? = null,
    val state: de.wanderlogplus.shared.query.DeadlineState = de.wanderlogplus.shared.query.DeadlineState.UPCOMING,
    val targetType: de.wanderlogplus.shared.query.DeadlineTarget? = null,
    val targetId: String? = null,
    val placeStatus: de.wanderlogplus.shared.model.PlaceStatus? = null,
)

/** Places show their deadlines on the today screen only in these states; ideas get a folded summary. */
internal val DEADLINE_PLACE_STATES = setOf(de.wanderlogplus.shared.model.PlaceStatus.SHORTLIST, de.wanderlogplus.shared.model.PlaceStatus.PLANNED)

/** Ideas are summarised when a deadline is this close (or open now / overdue). */
internal const val IDEA_DEADLINE_DAYS = 14L

/** Deadlines shown on the today screen: from [today] up to this many days ahead (overdue and open-now always). */
internal const val DEADLINE_DAYS = 30L

/**
 * The "Fristen" rows: check-out today first, then overdue, then the rest of the next [DEADLINE_DAYS] days by date;
 * places only when „Vorgemerkt“ or „Geplant“ (ideas: [ideaDeadlineRows]).
 */
internal fun deadlineRows(snapshot: TripSnapshot, today: String): List<DeadlineRow> {
    val last = LocalDate.parse(today).plusDays(DEADLINE_DAYS).toString()
    val rows = allDeadlineRows(snapshot, today)
        .filter { it.placeStatus == null || it.placeStatus in DEADLINE_PLACE_STATES }
        .filter { it.state != DeadlineState.UPCOMING || it.date <= last }
        .let(::retag)
    // Check-out today (with its time when known) comes first.
    val out = snapshot.stays.filter { it.checkOut == today }.mapIndexed { i, s ->
        DeadlineRow(today, snapshot.places[s.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft", de.wanderlogplus.app.ui.design.checkOutText(s) + " (heute)", "checkout-$i", targetType = DeadlineTarget.STAY, targetId = s.id)
    }
    return out + rows
}

/** Deadlines of ideas within [IDEA_DEADLINE_DAYS] days (or open now / overdue), for the folded "n Ideen mit Fristen". */
internal fun ideaDeadlineRows(snapshot: TripSnapshot, today: String): List<DeadlineRow> {
    val last = LocalDate.parse(today).plusDays(IDEA_DEADLINE_DAYS).toString()
    return allDeadlineRows(snapshot, today)
        .filter { it.placeStatus == de.wanderlogplus.shared.model.PlaceStatus.IDEA && (it.state != DeadlineState.UPCOMING || it.date <= last) }
        .mapIndexed { i, r -> r.copy(tag = "idea-$i") }
}

/** Numbers the tags per kind ("deadline-0", "reservation-0", "window-0") in the order shown. */
private fun retag(rows: List<DeadlineRow>): List<DeadlineRow> {
    val counters = mutableMapOf<String, Int>()
    return rows.map { r ->
        val prefix = r.tag
        r.copy(tag = "$prefix-" + counters.getOrDefault(prefix, 0).also { counters[prefix] = it + 1 })
    }
}

/** Every deadline as a row; [tag] holds only the kind prefix until [retag]. */
private fun allDeadlineRows(snapshot: TripSnapshot, today: String): List<DeadlineRow> {
    val input = de.wanderlogplus.shared.query.DeadlineInput(
        snapshot.places, snapshot.savedPlaces, snapshot.stays, snapshot.trains, snapshot.flights, snapshot.days, snapshot.dayItems,
        snapshot.queries()?.days().orEmpty(),
    )
    val savedById = snapshot.savedPlaces.associateBy { it.id }
    return de.wanderlogplus.shared.query.Deadlines.list(input, today)
        .map { d ->
            val est = if (d.estimated) " (geschätzt)" else ""
            val text = when {
                d.kind == DeadlineKind.CANCEL -> "Stornofrist bis ${dayLabel(d.date)}"
                d.kind == DeadlineKind.PLACE -> "Eingeplant ${dayLabel(d.date)} · " + (savedById[d.targetId]?.let { de.wanderlogplus.shared.query.Reservations.reason(it) } ?: "Reservierung nötig")
                d.kind == DeadlineKind.OPENS -> "Reservierbar ab ${dayLabel(d.date)}$est"
                d.state == DeadlineState.OVERDUE -> "Überfällig: reservieren bis ${dayLabel(d.date)}$est"
                d.state == DeadlineState.OPEN_NOW -> "Jetzt reservierbar · bis ${dayLabel(d.date)}$est"
                else -> "Reservieren bis ${dayLabel(d.date)}$est"
            }
            val prefix = when {
                d.kind == DeadlineKind.CANCEL -> "deadline"
                d.targetType == DeadlineTarget.PLACE -> "reservation"
                else -> "window"
            }
            DeadlineRow(d.date, d.title, text, prefix, d.targetId.takeIf { d.targetType == DeadlineTarget.PLACE }, d.state, d.targetType, d.targetId, d.placeStatus)
        }
}

/** Rows shown before "Alle Fristen (n)". */
internal const val DEADLINES_FOLDED = 4

/** "Fristen": overdue first (red), then by date; folded after [DEADLINES_FOLDED] rows. */
@Composable
internal fun DeadlinesWidget(
    rows: List<DeadlineRow>,
    accent: Color,
    onOpen: ((de.wanderlogplus.shared.query.DeadlineTarget, String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Ideas with deadlines soon: one folded line, opened on tap. */
    ideas: List<DeadlineRow> = emptyList(),
) {
    var all by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showIdeas by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Widget("Fristen", "today-deadlines", modifier, accent) {
        (if (all) rows else rows.take(DEADLINES_FOLDED)).forEach { r -> DeadlineLine(r, onOpen) }
        if (rows.size > DEADLINES_FOLDED) {
            androidx.compose.material3.TextButton(onClick = { all = !all }, modifier = Modifier.testTag("deadlines-more")) {
                Text(if (all) "Weniger anzeigen" else "Alle Fristen (${rows.size})")
            }
        }
        if (ideas.isNotEmpty()) {
            androidx.compose.material3.TextButton(onClick = { showIdeas = !showIdeas }, modifier = Modifier.testTag("deadlines-ideas")) {
                Text(if (ideas.size == 1) "1 Idee mit Fristen" else "${ideas.size} Ideen mit Fristen")
            }
            if (showIdeas) ideas.forEach { r -> DeadlineLine(r, onOpen) }
        }
    }
}

@Composable
private fun DeadlineLine(r: DeadlineRow, onOpen: ((de.wanderlogplus.shared.query.DeadlineTarget, String) -> Unit)?) {
    val type = r.targetType
    val id = r.targetId
    val click = if (type != null && id != null) onOpen?.let { open -> { open(type, id) } } else null
    val overdue = r.state == de.wanderlogplus.shared.query.DeadlineState.OVERDUE
    Row(
        Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier).padding(vertical = 3.dp).testTag(r.tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (r.savedPlaceId != null || type != de.wanderlogplus.shared.query.DeadlineTarget.STAY) Icons.Filled.ConfirmationNumber else Icons.Filled.EventBusy,
            contentDescription = if (r.tag.startsWith("deadline")) "Stornofrist" else "Reservierung",
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                r.text, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (overdue) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
            )
        }
    }
}

/** One line of "Jetzt geöffnet": the place and "1,2 km · offen bis 18:00". */
internal data class OpenNowRow(val suggestion: Suggestion, val info: String)

/** How far an open place may be to show up (planned or noted places show up at any distance). */
internal const val OPEN_NOW_KM = 5.0

/**
 * The open places worth a detour now: within [OPEN_NOW_KM] of [anchor] (where you are: the current or next stop, else
 * tonight's stay) or marked "Geplant"/"Vorgemerkt"; marked ones first, then by distance; at most [max].
 */
internal fun openNowRows(open: List<Suggestion>, anchor: de.wanderlogplus.shared.model.Place?, dow: Int, nowMin: Int, max: Int): List<OpenNowRow> {
    fun km(s: Suggestion): Double? {
        val p = s.place ?: return null
        val a = anchor ?: return null
        if ((p.lat == 0.0 && p.lng == 0.0) || (a.lat == 0.0 && a.lng == 0.0)) return null
        return DayNow.km(p.lat, p.lng, a.lat, a.lng)
    }
    fun marked(s: Suggestion) = s.saved.status == de.wanderlogplus.shared.model.PlaceStatus.PLANNED || s.saved.status == de.wanderlogplus.shared.model.PlaceStatus.SHORTLIST
    return open.map { it to km(it) }
        .filter { (s, d) -> marked(s) || (d != null && d <= OPEN_NOW_KM) }
        .sortedWith(compareBy({ !marked(it.first) }, { it.second ?: Double.MAX_VALUE }))
        .take(max)
        .map { (s, d) ->
            val open = s.place?.let { DayNow.openText(it.openingPeriods, dow, nowMin) }?.takeIf { it.startsWith("offen") }
            OpenNowRow(s, listOfNotNull(d?.let { kmText(it) }, open).joinToString(" · "))
        }
}

/** "Jetzt geöffnet": open places nearby or marked, one line each with distance and closing time. */
@Composable
internal fun OpenNowWidget(open: List<OpenNowRow>, accent: Color, modifier: Modifier = Modifier) {
    Widget("Jetzt geöffnet", "open-now", modifier, accent) {
        Column(Modifier.testTag("open-now-list")) {
            open.forEach { r ->
                val s = r.suggestion
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("open-now-${s.saved.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.info.isNotBlank()) Text(r.info, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


/**
 * "Für heute": the guides the day needs, in the order things happen (passed ones faded at the end); a tap shows a
 * short preview. [suggested] topics of the day without a guide link to the guide overview.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TodayGuidesWidget(
    dayGuides: List<de.wanderlogplus.shared.guide.DayGuide>,
    suggested: Int,
    accent: Color,
    onOpenGuide: (String) -> Unit,
    onSuggestions: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var preview by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<de.wanderlogplus.shared.model.Guide?>(null) }
    Widget("Für heute", "today-guides", modifier, accent) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dayGuides.forEach { d ->
                androidx.compose.material3.AssistChip(
                    onClick = { preview = d.guide },
                    label = {
                        Text(
                            listOfNotNull(d.atMinutes?.takeIf { !d.done }?.let { "%02d:%02d".format(it / 60, it % 60) }, d.guide.title).joinToString(" · "),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.padding(vertical = 2.dp).then(if (d.done) Modifier.alpha(0.5f) else Modifier).testTag("today-guide"),
                )
            }
        }
        if (suggested > 0 && onSuggestions != null) {
            androidx.compose.material3.TextButton(onClick = onSuggestions, modifier = Modifier.testTag("today-guides-suggested")) {
                Text(if (suggested == 1) "1 Guide vorgeschlagen" else "$suggested Guides vorgeschlagen")
            }
        }
    }
    preview?.let { g ->
        de.wanderlogplus.app.ui.design.EditSheet(g.title, onDismiss = { preview = null }) {
            if (g.summary.isNotBlank()) Text(g.summary, style = MaterialTheme.typography.bodyLarge)
            g.sections.flatMap { it.dos }.take(3).forEach { Text("✓ $it", style = MaterialTheme.typography.bodyMedium) }
            g.sections.flatMap { it.donts }.take(3).forEach { Text("✗ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            androidx.compose.material3.Button(onClick = { onOpenGuide(g.id); preview = null }, modifier = Modifier.testTag("today-guide-open")) { Text("Ganzen Guide öffnen") }
        }
    }
}
