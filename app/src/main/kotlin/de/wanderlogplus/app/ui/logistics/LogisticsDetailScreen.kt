package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
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
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Bed
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
import de.wanderlogplus.app.ui.design.roomPhotoOf
import de.wanderlogplus.app.ui.design.heroImage
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    /** Opens the journey this leg belongs to. */
    onOpenJourney: ((String) -> Unit)? = null,
    /** "Zu einer Reise zusammenführen…" (this leg plus the picked ones); null hides it. */
    onJoin: ((List<Pair<EntityType, String>>) -> Unit)? = null,
    /** "In Teilstrecken aufteilen" for an old merged entry; null hides it. */
    onSplit: ((EntityType, String) -> Unit)? = null,
    /** Stays: research room and amenities (links, fresh); null hides "Recherchieren". */
    onStayResearch: ((stayId: String, links: List<String>, fresh: Boolean) -> Unit)? = null,
    /** Stays: the research started last, to show its progress on its stay. */
    stayResearchJob: de.wanderlogplus.shared.sync.AiJobInfo? = null,
    /** Stays: mark a photo as room photo or remove the mark; null: tapping a photo does nothing. */
    onSetPhotoLabel: ((attachmentId: String, label: String?) -> Unit)? = null,
    /** Stays: open the stay's photos large at this gallery index (header image, photo strip); null: no viewer. */
    onOpenPhoto: ((index: Int) -> Unit)? = null,
    /** Stays: makes the photo attachment the header (after "Online suchen"); null hides that button. */
    onSetHeaderFromAttachment: ((EntityType, String, String) -> Unit)? = null,
    /** Stays: Google search for "Google-Eintrag"; null hides „Suchen“/„Ändern“. */
    googleSearch: de.wanderlogplus.app.ui.places.GoogleSearch? = null,
    /** Stays: links the stay's place to a Google entry (null: unlinks). */
    onLinkGoogle: ((placeId: String, hit: de.wanderlogplus.client.GeoPlace?, takePosition: Boolean) -> Unit)? = null,
    /** Trains: "Andere Fahrt suchen" (a missed train); null hides it. */
    onReplaceRide: ((trainId: String) -> Unit)? = null,
    /** Trains: "Passen noch" for seats entered for an earlier ride; null hides it. */
    onSeatsConfirmed: ((trainId: String) -> Unit)? = null,
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
    var choosingImage by remember(type, id) { mutableStateOf(false) }
    // Stays: 0 Übersicht, 1 Zimmer & Ausstattung, 2 Fotos.
    var stayTab by remember(type, id) { mutableStateOf(0) }
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
            onSearchOnline = attachments?.onSearchPhotos?.takeIf { onSetHeaderFromAttachment != null }?.let { search ->
                {
                    choosingImage = false
                    search(de.wanderlogplus.app.ui.attachments.AttachmentTarget.Entity(EntityType.STAY, id), true) { ids ->
                        ids.firstOrNull()?.let { onSetHeaderFromAttachment?.invoke(EntityType.STAY, id, it) }
                    }
                }
            },
        )
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("logistics-detail")) {
        val stayPhotosAll = if (type == EntityType.STAY) snapshot.photosFor(EntityType.STAY, id) else emptyList()
        // Tapping the header shows the stay's photos, starting with the chosen header photo.
        val headerIndex = snapshot.stays.firstOrNull { it.id == id }?.headerImage?.removePrefix("att:")
            ?.let { ref -> stayPhotosAll.indexOfFirst { it.id == ref } }?.takeIf { it >= 0 } ?: 0
        HeroHeader(
            modifier = if (onOpenPhoto != null && stayPhotosAll.isNotEmpty()) Modifier.clickable { onOpenPhoto(headerIndex) } else Modifier,
            title = view.title,
            kicker = view.kicker,
            imageUrl = view.imageUrl,
            accent = view.accent,
            onBack = onBack,
            titleMaxLines = 2,
            onChangeImage = if (stay != null && onSetHeaderImage != null) ({ choosingImage = true }) else null,
        )
        RouteMap(view.route, view.accent, height = 160.dp)
        if (type == EntityType.TRAIN || type == EntityType.FLIGHT) {
            val journey = remember(snapshot, type, id) { de.wanderlogplus.client.journeyOf(snapshot, type, id) }
            journey?.let { j ->
                val i = j.indexOf(type, id)
                androidx.compose.material3.Surface(
                    onClick = { onOpenJourney?.invoke(j.id) },
                    enabled = onOpenJourney != null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("leg-journey"),
                ) {
                    Text(
                        "Teil der Reise ${j.first.depart.name.ifBlank { j.first.depart.code ?: "?" }} → ${j.last.arrive.name.ifBlank { j.last.arrive.code ?: "?" }} (${i + 1}/${j.legs.size})" +
                            (if (onOpenJourney != null) " · ansehen" else ""),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp),
                    )
                }
            }
            LegActions(snapshot, type, id, journey?.id, onEdit, onJoin, onSplit, onDelete)
            if (type == EntityType.TRAIN && onReplaceRide != null) {
                OutlinedButton(onClick = { onReplaceRide(id) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("train-replace")) {
                    Text("Andere Fahrt suchen")
                }
            }
        } else if (type == EntityType.STAY) {
            MoveActions(snapshot, type, id, onEdit, null, onDelete)
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = stayTab) {
                de.wanderlogplus.app.ui.design.ScreenTab("Übersicht", stayTab == 0, "stay-tab-overview") { stayTab = 0 }
                de.wanderlogplus.app.ui.design.ScreenTab("Zimmer", stayTab == 1, "stay-tab-room", maxLines = 1) { stayTab = 1 }
                de.wanderlogplus.app.ui.design.ScreenTab(if (stayPhotosAll.isEmpty()) "Fotos" else "Fotos (${stayPhotosAll.size})", stayTab == 2, "stay-tab-photos") { stayTab = 2 }
            }
        }

        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (stay == null || stayTab == 0) {
            if (stay != null && stayPhotosAll.isNotEmpty()) {
                // Room photos first, then the rest.
                val order = stayPhotosAll.indices.sortedBy { if (stayPhotosAll[it].label == de.wanderlogplus.shared.model.Attachment.LABEL_ROOM) 0 else 1 }.take(8)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    de.wanderlogplus.app.ui.attachments.PhotoStrip(
                        stayPhotosAll, order, attachments?.files, onOpen = { i -> onOpenPhoto?.invoke(i) }, tag = "stay-photo-strip", modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = { stayTab = 2 }) { Text("Alle") }
                }
            }
            snapshot.places[stay?.placeId]?.let { p ->
                de.wanderlogplus.app.ui.places.GoogleLinkRow(
                    p, listOfNotNull(p.name.ifBlank { null }, snapshot.regions.firstOrNull { it.id == stay?.regionId }?.name).joinToString(" "),
                    googleSearch.takeIf { onLinkGoogle != null },
                    onLinkGoogle?.let { link -> { h: de.wanderlogplus.client.GeoPlace?, take: Boolean -> link(p.id, h, take) } },
                    Modifier.padding(top = 12.dp),
                    compact = true,
                    showAddress = true,
                )
            }
            if (stay != null) StayTimes(stay)
            SectionLabel("Details", color = view.accent)
            if (type == EntityType.TRAIN) snapshot.trains.firstOrNull { it.id == id }?.let { t ->
                t.walkFrom?.let { WalkEndRow(snapshot, it, t.depart, before = true, tag = "walk-from", modifier = Modifier.padding(bottom = 6.dp)) }
                t.walkTo?.let { WalkEndRow(snapshot, it, t.arrive, before = false, tag = "walk-to", modifier = Modifier.padding(bottom = 6.dp)) }
            }
            if (view.details.isNotEmpty()) InfoCard {
                view.details.forEach { (label, value) -> DetailRow(label, value) }
                if (view.liveStatus && type == EntityType.FLIGHT) FlightLiveCard(id)
                if (type == EntityType.TRAIN) TrainLiveCard(id)
            }
            if (stay != null) StayBooking(stay)
            if (type == EntityType.TRAIN) snapshot.trains.firstOrNull { it.id == id }?.transit?.routes?.firstOrNull()?.let { TrainStops(it) }

            val train = if (type == EntityType.TRAIN) snapshot.trains.firstOrNull { it.id == id } else null
            val flight = if (type == EntityType.FLIGHT) snapshot.flights.firstOrNull { it.id == id } else null
            val seatModel = train?.let { de.wanderlogplus.shared.seatmap.SeatMaps.modelFor(it) } ?: flight?.let { de.wanderlogplus.shared.seatmap.SeatMaps.modelFor(it) }
            val seats = train?.seats ?: flight?.seats.orEmpty()
            val travelClass = train?.travelClass ?: flight?.travelClass
            if (seats.isNotEmpty() || !travelClass.isNullOrBlank()) {
                SectionLabel("Sitzplätze", color = view.accent)
                if (train != null && seatsStale(train)) {
                    Row(Modifier.fillMaxWidth().testTag("train-seats-stale"), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Sitzplätze galten für die alte Fahrt (" + train.seatsForDeparture.orEmpty().substringAfter('T') + ")",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        onSeatsConfirmed?.let { ok -> androidx.compose.material3.TextButton(onClick = { ok(train.id) }, modifier = Modifier.testTag("train-seats-ok")) { Text("Passen noch") } }
                    }
                }
                InfoCard { SeatCard(seatModel, travelClass, seats) }
            }

            val guideTarget = when (type) {
                EntityType.STAY -> de.wanderlogplus.shared.model.GuideTargetType.STAY
                EntityType.TRAIN -> de.wanderlogplus.shared.model.GuideTargetType.TRAIN
                EntityType.FLIGHT -> de.wanderlogplus.shared.model.GuideTargetType.FLIGHT
                else -> null
            }
            val openGuide = de.wanderlogplus.app.ui.guides.LocalOpenGuide.current
            if (guideTarget != null && openGuide != null) {
                de.wanderlogplus.app.ui.guides.GuidesSection(
                    snapshot, de.wanderlogplus.shared.model.GuideTarget(guideTarget, id), openGuide, Modifier.padding(top = 12.dp),
                )
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
            }

            if (stay != null && stayTab == 1) {
                StayInfoCards(
                    modifier = Modifier.padding(top = 12.dp),
                    stay = stay,
                    roomPhotos = stayPhotosAll.filter { it.label == de.wanderlogplus.shared.model.Attachment.LABEL_ROOM },
                    // Room photos open at their place in the stay's whole gallery.
                    onOpenPhoto = { i ->
                        stayPhotosAll.filter { it.label == de.wanderlogplus.shared.model.Attachment.LABEL_ROOM }.getOrNull(i)?.let { onOpenPhoto?.invoke(stayPhotosAll.indexOf(it)) }
                    },
                    files = attachments?.files,
                    onResearch = onStayResearch?.let { r -> { links: List<String>, fresh: Boolean -> r(id, links, fresh) } },
                    onEdit = onEdit?.let { e -> { e(EntityType.STAY, id) } },
                    running = stayResearchJob?.takeIf { it.stayId == id },
                )
            }

            if (stay != null && stayTab == 2) {
                // Google's photos of the stay, as on a place's page.
                de.wanderlogplus.app.ui.places.PlacePhotosRow(snapshot.places[stay.placeId], Modifier.padding(top = 12.dp))
            }

            if (stay != null && stayTab == 2 && attachments != null) {
                val stayPhotos = stayPhotosAll
                // "Zimmerfotos wählen": tick all room photos at once instead of a dialog per photo.
                var picking by remember(id) { mutableStateOf<Set<String>?>(null) }
                val chosen = picking
                if (onSetPhotoLabel != null && stayPhotos.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (chosen == null) {
                            Box(Modifier.weight(1f))
                            androidx.compose.material3.TextButton(
                                onClick = { picking = stayPhotos.filter { it.label == de.wanderlogplus.shared.model.Attachment.LABEL_ROOM }.map { it.id }.toSet() },
                                modifier = Modifier.testTag("room-photos-pick"),
                            ) {
                                Icon(androidx.compose.material.icons.Icons.Filled.Bed, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                                Text("Zimmerfotos wählen")
                            }
                        } else {
                            Text("Tippe die Fotos eures Zimmers an", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { picking = null }, modifier = Modifier.testTag("room-photos-cancel")) { Text("Abbrechen") }
                            androidx.compose.material3.TextButton(
                                onClick = { roomLabelChanges(stayPhotos, chosen).forEach { (pid, label) -> onSetPhotoLabel(pid, label) }; picking = null },
                                modifier = Modifier.testTag("room-photos-done"),
                            ) { Text("Fertig") }
                        }
                    }
                }
                de.wanderlogplus.app.ui.attachments.PhotoGrid(
                    stayPhotos, attachments.files,
                    onAdd = if (chosen == null) attachments.onAddPhotos?.let { add -> { add(EntityType.STAY, id) } } else null,
                    onOpen = { i ->
                        val p = stayPhotos.getOrNull(i)
                        if (chosen != null && p != null) picking = if (p.id in chosen) chosen - p.id else chosen + p.id else onOpenPhoto?.invoke(i)
                    },
                    selected = chosen,
                )
            }

            if (stay == null || stayTab == 0) {
                SectionLabel("Anhänge", color = view.accent)
                if (attachments == null) {
                    EmptyHint("Anhänge sind hier nicht verfügbar", Modifier.padding(top = 6.dp))
                } else {
                    AttachmentsFor(snapshot.attachmentsFor(type, id), attachments, onAdd = { attachments.onAdd(type, id) }, showTitle = false)
                }
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
                    de.wanderlogplus.shared.seatmap.seatLine(f.travelClass, f.seats)?.let { "Sitzplätze" to it },
                    f.reservation?.let { "Reservierung" to reservationText(it, estimated = false) },
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
                    de.wanderlogplus.shared.seatmap.seatLine(t.travelClass, t.seats)?.let { "Sitzplätze" to it },
                    t.reservation?.let { "Reservierung" to reservationText(it, estimated = false) },
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
            val deadline = s.cancellationDeadline?.takeIf { it.isNotBlank() }?.let { cancellationText(it, today) }
            BookingView(
                title = stayName(snapshot, s),
                kicker = stayPeriod(s),
                imageUrl = s.heroImage(place, snapshot.roomPhotoOf(s.id)),
                accent = region?.let { regionColor(it.color) } ?: primary,
                route = null,
                // Check-in/out stand as tiles above, booking number, travellers and conditions folded under "Buchung".
                details = listOfNotNull(
                    guests.takeIf { it.isNotEmpty() }?.let { "Gäste" to it },
                    deadline?.let { "Stornierung" to it },
                    s.reservation?.let { "Reservierung" to reservationText(it, estimated = false) },
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

/** Actions of a train/flight: edit, join into a journey (pick legs), split an old merged entry, delete. */
@Composable
private fun LegActions(
    snapshot: TripSnapshot,
    type: EntityType,
    id: String,
    journeyId: String?,
    onEdit: ((EntityType, String) -> Unit)?,
    onJoin: ((List<Pair<EntityType, String>>) -> Unit)?,
    onSplit: ((EntityType, String) -> Unit)?,
    onDelete: ((EntityType, String) -> Unit)?,
) {
    var joining by remember(type, id) { mutableStateOf(false) }
    var confirmSplit by remember(type, id) { mutableStateOf(false) }
    val legacy = journeyId == null && when (type) {
        EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.via?.isNotEmpty() == true
        EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.via?.isNotEmpty() == true
        else -> false
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        onEdit?.let { e -> OutlinedButton(onClick = { e(type, id) }, modifier = Modifier.testTag("move-edit")) { Text("Bearbeiten") } }
        if (onJoin != null) OutlinedButton(onClick = { joining = true }, modifier = Modifier.testTag("move-join")) { Text(if (journeyId != null) "Weitere Fahrt zur Reise" else "Zu Reise zusammenführen") }
        if (onSplit != null && legacy) OutlinedButton(onClick = { confirmSplit = true }, modifier = Modifier.testTag("move-split")) { Text("In Teilstrecken aufteilen") }
    }
    // Delete keeps its confirmation from MoveActions.
    MoveActions(snapshot, type, id, null, null, onDelete)
    if (joining && onJoin != null) {
        val around = de.wanderlogplus.client.legOf(
            if (type == EntityType.FLIGHT) de.wanderlogplus.client.movementOf(snapshot.flights.first { it.id == id }) else de.wanderlogplus.client.movementOf(snapshot.trains.first { it.id == id }),
        ).departs
        JoinDialog(snapshot, journeyId, around, emptySet(), exclude = setOf(type to id), onDismiss = { joining = false }) { picked ->
            joining = false
            onJoin(listOf(type to id) + picked)
        }
    }
    if (confirmSplit && onSplit != null) {
        AlertDialog(
            onDismissRequest = { confirmSplit = false },
            title = { Text("In Teilstrecken aufteilen?") },
            text = { Text("Aus jedem Zwischenhalt wird eine eigene Teilstrecke derselben Reise. Abfahrtszeiten und Nummern der neuen Strecken trägst du danach ein (z. B. per Verbindungssuche).") },
            confirmButton = { TextButton(onClick = { confirmSplit = false; onSplit(type, id) }, modifier = Modifier.testTag("split-confirm")) { Text("Aufteilen") } },
            dismissButton = { TextButton(onClick = { confirmSplit = false }) { Text("Abbrechen") } },
        )
    }
}

/** Seats entered for an earlier departure of this train (it was replaced by another ride). */
fun seatsStale(t: de.wanderlogplus.shared.model.Train): Boolean =
    t.seats.isNotEmpty() && t.seatsForDeparture != null && t.seatsForDeparture != listOfNotNull(t.depart.date, t.depart.time).joinToString("T")

/**
 * "Zwischenhalte (n)" to unfold: for each ride of the connection the boarding station with its departure, every stop
 * between with its time and the station where one leaves with the arrival.
 */
@Composable
internal fun TrainStops(route: de.wanderlogplus.shared.sync.TransitRoute) {
    val rides = route.legs.filter { !it.walk }
    val count = rides.sumOf { it.stops.size }
    if (count == 0) return
    var open by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = { open = !open }, modifier = Modifier.testTag("train-stops")) {
        Text((if (open) "▾ " else "▸ ") + "Zwischenhalte ($count)")
    }
    if (!open) return
    var n = 0
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rides.forEach { leg ->
            fun name(s: de.wanderlogplus.shared.sync.TransitStation) = s.name.ifBlank { s.nameJa }
            if (rides.size > 1) Text(leg.line.ifBlank { leg.lineJa }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            Text("${leg.departure} ${name(leg.from)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            leg.stops.forEach { s ->
                Text(
                    "${s.time} ${s.name.ifBlank { s.nameJa }}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp).testTag("train-stop-${n++}"),
                )
            }
            Text("${leg.arrival} ${name(leg.to)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Kostenlos stornierbar bis Fr, 07.05., 23:59", or "Stornofrist abgelaufen (…)" once [today] is past it. */
fun cancellationText(deadline: String, today: String?): String {
    val date = deadline.take(10)
    // "2027-05-07T23:59" or, as imported from Wanderlog, "2027-05-07 23:59".
    val time = deadline.drop(10).trim().removePrefix("T").take(5).takeIf { it.matches(Regex("""\d{1,2}:\d{2}""")) }
    val text = dayLabel(date) + (time?.let { ", $it" } ?: "")
    val past = today != null && today > date
    return if (past) "Stornofrist abgelaufen ($text)" else "Kostenlos stornierbar bis $text"
}

/** Check-in and check-out of a stay as two tiles side by side (tags `stay-checkin`, `stay-checkout`). */
@Composable
private fun StayTimes(stay: de.wanderlogplus.shared.model.Stay) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple("Check-in", stayTimeText(stay.checkIn, stay.checkInTime, checkIn = true), "stay-checkin"),
            Triple("Check-out", stayTimeText(stay.checkOut, stay.checkOutTime, checkIn = false), "stay-checkout"),
        ).forEach { (label, value, tag) ->
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f).testTag(tag)) {
                Column(Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Booking number (selectable to copy), travellers and conditions of a stay, folded under "Buchung": not on show when
 * the phone is passed around. Nothing without such data. Tag `stay-booking`.
 */
@Composable
private fun StayBooking(stay: de.wanderlogplus.shared.model.Stay) {
    val rows = booking(stay.confirmationNumber, stay.travelerNames) +
        listOfNotNull(stay.cancellationPolicy?.takeIf { it.isNotBlank() }?.let { "Stornobedingungen" to it })
    if (rows.isEmpty()) return
    var open by remember(stay.id) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp).testTag("stay-booking")) {
        Text(if (open) "Buchung ausblenden" else "Buchung (Bestätigungsnummer, Reisende, Bedingungen)")
    }
    if (open) InfoCard {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEach { (label, value) -> DetailRow(label, value) } }
        }
    }
}
