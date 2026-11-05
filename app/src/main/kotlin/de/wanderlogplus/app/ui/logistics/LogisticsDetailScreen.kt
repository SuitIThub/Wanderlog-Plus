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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import de.wanderlogplus.shared.model.Endpoint
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
    /** Null hides the back button (detail pane beside its list). */
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    attachments: AttachmentActions? = null,
    /** Null hides the stay navigation buttons. */
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    /** ISO date (trip zone); a passed cancellation deadline is marked "abgelaufen". Null marks none. */
    today: String? = null,
    /** Trains and flights only; null hides the action. */
    onEdit: ((EntityType, String) -> Unit)? = null,
    /** Joins this leg with the next one of the same kind ([nextLegOf]); null hides the action. */
    onMerge: ((type: EntityType, firstId: String, secondId: String) -> Unit)? = null,
    /** Deletes this train or flight (after confirmation); the caller navigates back. Null hides the action. */
    onDelete: ((EntityType, String) -> Unit)? = null,
    /** Own header image of a stay; null hides "Titelbild ändern". */
    onSetHeaderImage: ((EntityType, String, String?) -> Unit)? = null,
    /** Own links; null hides "Link". */
    onSetLinks: ((EntityType, String, List<de.wanderlogplus.shared.model.CustomLink>) -> Unit)? = null,
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
    var choosingImage by remember(type, id) { mutableStateOf(false) }
    val stay = if (type == EntityType.STAY) snapshot.stays.firstOrNull { it.id == id } else null
    val customLinks = when (type) {
        EntityType.STAY -> stay?.links
        EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.links
        EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.links
        else -> null
    }.orEmpty()
    if (choosingImage && stay != null && onSetHeaderImage != null) {
        de.wanderlogplus.app.ui.places.HeaderImagePicker(
            snapshot.places[stay.placeId], snapshot.photosFor(EntityType.STAY, id), attachments?.files, stay.headerImage,
            onPick = { ref -> onSetHeaderImage(type, id, ref); choosingImage = false },
            onUpload = attachments?.onAddPhotos?.let { add -> { add(EntityType.STAY, id) } },
            onDismiss = { choosingImage = false },
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("logistics-detail")) {
        HeroHeader(
            title = view.title,
            kicker = view.kicker,
            imageUrl = view.imageUrl,
            accent = view.accent,
            height = 140.dp,
            onBack = onBack,
            titleMaxLines = 1,
            onTitleOverflow = { titleCut = it },
            onChangeImage = if (stay != null && onSetHeaderImage != null) ({ choosingImage = true }) else null,
        )
        RouteMap(view.route, view.accent, height = 160.dp)
        if (type == EntityType.TRAIN || type == EntityType.FLIGHT || type == EntityType.STAY) {
            MoveActions(snapshot, type, id, onEdit, onMerge, onDelete)
        }

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
                if (view.liveStatus && type == EntityType.FLIGHT) FlightLiveCard(id)
                if (type == EntityType.TRAIN) TrainLiveCard(id)
            }

            SectionLabel("Notiz", color = view.accent)
            if (view.note.isNotBlank()) {
                InfoCard { MarkdownText(view.note, Modifier.testTag("logistics-note")) }
            } else {
                EmptyHint("Keine Notiz", Modifier.padding(top = 6.dp))
            }

            if ((onOpenLink != null && (view.links.isNotEmpty() || customLinks.isNotEmpty())) || onSetLinks != null) {
                SectionLabel("Links", color = view.accent)
                de.wanderlogplus.app.ui.design.LinkRow(
                    view.links, customLinks, onOpenLink,
                    onSetLinks?.let { set -> { l: List<de.wanderlogplus.shared.model.CustomLink> -> set(type, id, l) } },
                )
            }

            de.wanderlogplus.app.ui.budget.CostsSection(snapshot, id, today ?: java.time.LocalDate.now().toString(), view.accent)

            if (type == EntityType.STAY && attachments != null) {
                SectionLabel("Fotos", color = view.accent)
                de.wanderlogplus.app.ui.attachments.PhotoGrid(
                    snapshot.photosFor(EntityType.STAY, id), attachments.files,
                    onAdd = attachments.onAddPhotos?.let { add -> { add(EntityType.STAY, id) } },
                    onOpen = {},
                )
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
                    viaDetail(f.via),
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
                details = listOfNotNull(
                    "Strecke" to Movement.TrainMove(t).routeTitle(),
                    t.number?.takeIf { it.isNotBlank() }?.let { "Zug" to it },
                    viaDetail(t.via),
                    "Abfahrt" to endpointText(t.depart) + (t.platformText()?.let { " · $it" } ?: ""),
                    "Ankunft" to endpointText(t.arrive),
                    t.transit?.routes?.firstOrNull()?.let { r -> "Verbindung" to transitSummary(r) },
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
                imageUrl = de.wanderlogplus.app.ui.design.HeaderImages.url(s.headerImage) ?: place?.heroImageUrl(),
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

/** "Zwischenhalte" row: one line per stop with its time; null without stopovers. */
private fun viaDetail(via: List<Endpoint>): Pair<String, String>? =
    via.takeIf { it.isNotEmpty() }?.let { stops ->
        "Zwischenhalte" to stops.joinToString("\n") { e -> listOfNotNull(e.name.ifBlank { e.code ?: "?" }, e.time?.takeIf { it.isNotBlank() }).joinToString(" · ") }
    }

/** The next train (or flight) after [id] in chronological order, the natural partner for "Zusammenführen"; null if none. */
fun nextLegOf(snapshot: TripSnapshot, type: EntityType, id: String): String? {
    val sameKind = logisticsEntries(snapshot).filter {
        (type == EntityType.TRAIN && it is LogisticsEntry.TrainEntry) || (type == EntityType.FLIGHT && it is LogisticsEntry.FlightEntry)
    }
    val i = sameKind.indexOfFirst { it.id == id }
    return if (i < 0) null else sameKind.getOrNull(i + 1)?.id
}

private fun legLabel(snapshot: TripSnapshot, type: EntityType, id: String): String = when (type) {
    EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.let { Movement.TrainMove(it).routeTitle() + (it.depart.time?.let { t -> " ($t)" } ?: "") }
    EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.let { Movement.FlightMove(it).routeTitle() + (it.depart.time?.let { t -> " ($t)" } ?: "") }
    else -> null
} ?: "?"

/** Bearbeiten / Zusammenführen / Löschen for a train or flight; merging and deleting ask first. */
@Composable
private fun MoveActions(
    snapshot: TripSnapshot,
    type: EntityType,
    id: String,
    onEdit: ((EntityType, String) -> Unit)?,
    onMerge: ((EntityType, String, String) -> Unit)?,
    onDelete: ((EntityType, String) -> Unit)?,
) {
    val next = remember(snapshot, type, id) { nextLegOf(snapshot, type, id) }
    var confirmMerge by remember(type, id) { mutableStateOf(false) }
    var confirmDelete by remember(type, id) { mutableStateOf(false) }
    if (onEdit == null && onMerge == null && onDelete == null) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        onEdit?.let { e -> OutlinedButton(onClick = { e(type, id) }, modifier = Modifier.testTag("move-edit")) { Text("Bearbeiten") } }
        if (onMerge != null && next != null) {
            OutlinedButton(onClick = { confirmMerge = true }, modifier = Modifier.testTag("move-merge")) { Text("Mit nächster zusammenführen") }
        }
        onDelete?.let { OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("move-delete")) { Text("Löschen") } }
    }
    if (confirmMerge && onMerge != null && next != null) {
        AlertDialog(
            onDismissRequest = { confirmMerge = false },
            title = { Text("Zusammenführen?") },
            text = {
                Text(
                    "„${legLabel(snapshot, type, id)}“ und „${legLabel(snapshot, type, next)}“ werden eine Fahrt; " +
                        "das bisherige Ziel wird zum Zwischenhalt. Anhänge wandern mit.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmMerge = false; onMerge(type, id, next) }, modifier = Modifier.testTag("merge-confirm")) { Text("Zusammenführen") }
            },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(when (type) { EntityType.FLIGHT -> "Flug löschen?"; EntityType.STAY -> "Unterkunft löschen?"; else -> "Fahrt löschen?" }) },
            text = { Text("„${legLabel(snapshot, type, id)}“ wird entfernt. Ein Wanderlog-Import stellt sie nicht wieder her.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(type, id) }, modifier = Modifier.testTag("delete-confirm")) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
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

/** "Gleis 14" of the first ride of the chosen connection; null without one. */
fun de.wanderlogplus.shared.model.Train.platformText(): String? =
    transit?.routes?.firstOrNull()?.legs?.firstOrNull { !it.walk }?.departurePlatform?.takeIf { it.isNotBlank() }?.let { "Gleis $it" }

/** "Kyoto 08:12 → Nagoya 08:47 (Gleis 14) → Nakatsugawa 09:40": the rides of a connection. */
internal fun transitSummary(r: de.wanderlogplus.shared.sync.TransitRoute): String {
    val rides = r.legs.filter { !it.walk }
    if (rides.isEmpty()) return "${r.departure}–${r.arrival}"
    return rides.joinToString("\n") { l ->
        val line = l.line.ifBlank { l.lineJa }
        val from = l.from.name.ifBlank { l.from.nameJa }
        val to = l.to.name.ifBlank { l.to.nameJa }
        "$line: $from ${l.departure}" + (l.departurePlatform?.takeIf { it.isNotBlank() }?.let { " (Gleis $it)" } ?: "") + " → $to ${l.arrival}"
    }
}
