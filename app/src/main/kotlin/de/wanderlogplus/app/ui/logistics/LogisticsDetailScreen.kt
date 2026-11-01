package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.maps.RouteGeometry
import de.wanderlogplus.app.maps.RouteMap
import de.wanderlogplus.app.maps.routeFor
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsFor
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.ActionCircle
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.design.routeTitle
import de.wanderlogplus.app.ui.places.linkIcon
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.stayLinks
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.sync.EntityType

private val CardShape = RoundedCornerShape(18.dp)

/** What the detail page shows of one booking; [details] are label/value rows (booking data only lives here). */
private class BookingView(
    val title: String,
    val kicker: String?,
    val imageUrl: String?,
    val accent: Color,
    val route: RouteGeometry?,
    val details: List<Pair<String, String>>,
    val note: String,
    val liveStatus: Boolean,
    val links: List<ExternalLink>,
)

/**
 * Booking detail of a flight, train or stay: hero (route map below it for flights and trains, photo for stays), then
 * "Details" (endpoints, times, confirmation number, travelers, cancellation), "Notiz", "Navigation" (stays only) and
 * "Anhänge". The page scrolls. An unknown [type] (null: bad route segment) or [id] shows "Buchung nicht gefunden" with back.
 */
@Composable
fun LogisticsDetailScreen(
    snapshot: TripSnapshot,
    type: EntityType?,
    id: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: AttachmentActions? = null,
    /** Null hides the stay navigation buttons. */
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    /** ISO date (trip zone); a passed cancellation deadline is marked "abgelaufen". Null marks none. */
    today: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val view = remember(snapshot, type, id, today, primary) { bookingView(snapshot, type, id, today, primary) }

    if (view == null || type == null) {
        Column(modifier.fillMaxSize()) {
            HeroHeader("Buchung nicht gefunden", kicker = null, imageUrl = null, accent = primary, height = 140.dp, onBack = onBack, titleMaxLines = 1)
            EmptyHint("Diese Buchung existiert nicht mehr.", Modifier.padding(16.dp))
        }
        return
    }

    // The one-line hero cuts long names (stays); the full name then opens the page body.
    var titleCut by remember(type, id) { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeroHeader(
            title = view.title,
            kicker = view.kicker,
            imageUrl = view.imageUrl,
            accent = view.accent,
            height = 140.dp,
            onBack = onBack,
            titleMaxLines = 1,
            onTitleOverflow = { titleCut = it },
        )
        RouteMap(view.route, view.accent, height = 160.dp)

        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (titleCut) {
                Text(
                    view.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp).testTag("logistics-full-title"),
                )
            }
            SectionLabel("Details", color = view.accent)
            InfoCard {
                view.details.forEach { (label, value) -> DetailRow(label, value) }
                if (view.liveStatus) {
                    Text(
                        "Live-Status: –",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp).testTag("live-status"),
                    )
                }
            }

            SectionLabel("Notiz", color = view.accent)
            if (view.note.isNotBlank()) {
                InfoCard { MarkdownText(view.note, Modifier.testTag("logistics-note")) }
            } else {
                EmptyHint("Keine Notiz.", Modifier.padding(top = 6.dp))
            }

            if (onOpenLink != null && view.links.isNotEmpty()) {
                SectionLabel("Navigation", color = view.accent)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    view.links.forEach { l -> ActionCircle(linkIcon(l.kind), l.label, { onOpenLink(l) }, "link-${l.kind}") }
                }
            }

            SectionLabel("Anhänge", color = view.accent)
            if (attachments == null) {
                EmptyHint("Anhänge sind hier nicht verfügbar", Modifier.padding(top = 6.dp))
            } else {
                AttachmentsFor(snapshot.attachmentsFor(type, id), attachments, onAdd = { attachments.onAdd(type, id) }, showTitle = false)
            }
        }
    }
}

private fun bookingView(snapshot: TripSnapshot, type: EntityType?, id: String, today: String?, primary: Color): BookingView? =
    when (type) {
        EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.let { f ->
            BookingView(
                title = flightCardTitle(f),
                kicker = f.depart.date?.takeIf { it.isNotBlank() }?.let(::dayLabel),
                imageUrl = null,
                accent = primary,
                route = routeFor(f, snapshot),
                details = listOfNotNull(
                    "Strecke" to Movement.FlightMove(f).routeTitle(),
                    "Abflug" to endpointText(f.depart),
                    "Ankunft" to endpointText(f.arrive),
                    f.airline?.takeIf { it.isNotBlank() }?.let { "Airline" to it },
                ) + booking(f.confirmationNumber, f.travelerNames),
                note = f.note,
                liveStatus = true,
                links = emptyList(),
            )
        }
        EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.let { t ->
            BookingView(
                title = trainTitle(t),
                kicker = t.depart.date?.takeIf { it.isNotBlank() }?.let(::dayLabel),
                imageUrl = null,
                accent = primary,
                route = routeFor(t, snapshot),
                details = listOf(
                    "Strecke" to Movement.TrainMove(t).routeTitle(),
                    "Abfahrt" to endpointText(t.depart),
                    "Ankunft" to endpointText(t.arrive),
                ) + booking(t.confirmationNumber, t.travelerNames),
                note = t.note,
                liveStatus = true,
                links = emptyList(),
            )
        }
        EntityType.STAY -> snapshot.stays.firstOrNull { it.id == id }?.let { s ->
            val place = snapshot.places[s.placeId]
            val region = s.regionId?.let { rid -> snapshot.regions.firstOrNull { it.id == rid } }
            val guests = listOfNotNull(
                s.adults?.let { if (it == 1) "1 Erwachsener" else "$it Erwachsene" },
                s.rooms?.let { if (it == 1) "1 Zimmer" else "$it Zimmer" },
            ).joinToString(" · ")
            val deadline = s.cancellationDeadline?.takeIf { it.isNotBlank() }?.let {
                dayLabel(it) + if (today != null && !cancellationOpen(s, today)) " (abgelaufen)" else ""
            }
            BookingView(
                title = stayName(snapshot, s),
                kicker = stayPeriod(s),
                imageUrl = place?.heroImageUrl(),
                accent = region?.let { regionColor(it.color) } ?: primary,
                route = null,
                details = listOfNotNull(
                    "Check-in" to s.checkIn.ifBlank { "–" }.let(::dayLabel),
                    "Check-out" to s.checkOut.ifBlank { "–" }.let(::dayLabel),
                    guests.takeIf { it.isNotEmpty() }?.let { "Gäste" to it },
                ) + booking(s.confirmationNumber, s.travelerNames) + listOfNotNull(
                    deadline?.let { "Stornofrist" to it },
                    s.cancellationPolicy?.takeIf { it.isNotBlank() }?.let { "Stornobedingungen" to it },
                ),
                note = s.note,
                liveStatus = false,
                links = place?.let { stayLinks(it) }.orEmpty(),
            )
        }
        else -> null
    }

private fun booking(confirmation: String?, travelers: List<String>): List<Pair<String, String>> = listOfNotNull(
    confirmation?.takeIf { it.isNotBlank() }?.let { "Bestätigungsnummer" to it },
    travelers.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { "Reisende" to it.joinToString(", ") },
)

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
