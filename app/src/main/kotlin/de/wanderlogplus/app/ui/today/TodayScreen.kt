package de.wanderlogplus.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import de.wanderlogplus.app.ui.design.isWideLayout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.ui.EmptyState
import de.wanderlogplus.app.ui.HeroTitle
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.categorySymbol
import de.wanderlogplus.app.ui.dayKicker
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.DayTimeline
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.app.ui.design.PhotoCardStyle
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.heroGradient
import de.wanderlogplus.app.ui.design.dayHeroImage
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.stayLinks
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.TodayQueries
import de.wanderlogplus.shared.query.TodayState
import de.wanderlogplus.shared.query.isOpenAt
import de.wanderlogplus.shared.query.sortSuggestions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MAX_OPEN_NOW = 5
private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/** [now] is injected so tests can pin "today"; production passes the device's local time. */
@Composable
fun TodayScreen(
    snapshot: TripSnapshot,
    now: LocalDateTime,
    onOpenDay: (date: String) -> Unit,
    onOpenTrip: () -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tapped ticket chip under a trip of today. */
    onOpenAttachment: (Attachment) -> Unit = {},
    /** Null hides the stay link buttons. */
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    /** Whether a file is stored locally (by hash); unavailable tickets are marked. */
    available: (hash: String) -> Boolean = { true },
) {
    val trip = snapshot.trip
    val queries = remember(snapshot) { snapshot.queries() }
    val state = remember(snapshot, now.toLocalDate()) {
        if (trip == null || queries == null) TodayState.Unknown
        else TodayQueries(queries, trip, snapshot.stays, snapshot.flights, snapshot.trains).state(now.toLocalDate())
    }
    when (state) {
        TodayState.Unknown -> EmptyState(onSyncNow, modifier)
        is TodayState.Before -> Before(snapshot, state, modifier)
        TodayState.After -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Willkommen zurück", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onOpenTrip) { Text("Zur Reise") }
        }
        is TodayState.During -> During(snapshot, state, now, onOpenDay, onOpenAttachment, onOpenLink, available, modifier)
    }
}

@Composable
private fun Before(snapshot: TripSnapshot, state: TodayState.Before, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(heroGradient(accent))) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                Text(
                    if (state.daysUntilStart == 1L) "1 Tag" else "${state.daysUntilStart} Tage",
                    style = HeroTitle.copy(fontSize = HeroTitle.fontSize * 1.8f, lineHeight = HeroTitle.lineHeight * 1.8f),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.testTag("countdown"),
                )
                Text(
                    "bis ${snapshot.trip?.title?.takeIf { it.isNotBlank() } ?: "zur Reise"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.deadlines.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Anstehende Fristen")
                state.deadlines.forEachIndexed { i, d ->
                    val place = snapshot.places[d.stay.placeId]
                    PhotoCard(
                        title = place?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft",
                        subtitle = dayLabel(d.date),
                        imageUrl = place?.heroImageUrl(),
                        placeholderSymbol = "🏨",
                        accent = regionColor(snapshot.regions.firstOrNull { it.id == d.stay.regionId }?.color),
                        badges = listOf(BadgeSpec("Stornofrist", BadgeTone.Warning)),
                        modifier = Modifier.testTag("deadline-$i"),
                    )
                }
            }
        }
    }
}

@Composable
private fun During(
    snapshot: TripSnapshot,
    state: TodayState.During,
    now: LocalDateTime,
    onOpenDay: (String) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onOpenLink: ((ExternalLink) -> Unit)?,
    available: (String) -> Boolean,
    modifier: Modifier,
) {
    val view = state.day
    val date = view.date
    val region = view.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
    val dayTitle = view.day?.title?.takeIf { it.isNotBlank() }
    val stay = view.stay
    val stayPlace = stay?.let { snapshot.places[it.placeId] }
    val items = remember(snapshot, view.day) { view.day?.let { snapshot.dayItemsFor(it.id) }.orEmpty() }
    val heroImage = remember(snapshot, date) { dayHeroImage(snapshot, date) }
    val openNow = remember(snapshot, date, now) {
        val dow = now.dayOfWeek.value % 7 // 0 = Sunday
        val time = now.format(HH_MM)
        val all = snapshot.queries()?.suggestionsFor(date).orEmpty().map { Suggestion(it, snapshot.places[it.placeId]) }
        sortSuggestions(all.filter { isOpenAt(it.place?.openingPeriods.orEmpty(), dow, time) != false }).take(MAX_OPEN_NOW)
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().clickable { onOpenDay(date) }.testTag("today-header")) {
            HeroHeader(
                title = dayTitle ?: region?.name ?: dayLabel(date),
                kicker = dayKicker(date, snapshot.trip?.startDate, snapshot.trip?.endDate),
                imageUrl = heroImage,
                accent = accent,
                chips = if (dayTitle != null && region != null) listOf(region.name) else emptyList(),
            )
        }

        val stayBlock: @Composable () -> Unit = {
            if (stay != null) {
                SectionLabel("Unterkunft", color = accent)
                val hint = when (date) {
                    stay.checkIn -> "Check-in heute"
                    stay.checkOut -> "Check-out heute"
                    else -> "Check-out ${dayLabel(stay.checkOut)}"
                }
                PhotoCard(
                    title = stayPlace?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft",
                    subtitle = hint,
                    imageUrl = stayPlace?.heroImageUrl(),
                    placeholderSymbol = "🏨",
                    accent = accent,
                    trailing = if (stayPlace != null && onOpenLink != null) {
                        { StayActions(stayLinks(stayPlace), onOpenLink) }
                    } else null,
                    modifier = Modifier.testTag("today-stay"),
                )
            }
        }
        val openBlock: @Composable () -> Unit = {
            if (openNow.isNotEmpty()) {
                SectionLabel("Jetzt geöffnet", color = accent)
                Column(Modifier.testTag("open-now-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    openNow.forEach { s ->
                        PhotoCard(
                            title = s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort",
                            subtitle = listOfNotNull(
                                categoryLabel(s.saved.category),
                                s.place?.rating?.let { ratingLabel(it) },
                            ).joinToString(" · "),
                            imageUrl = s.place?.heroImageUrl(),
                            placeholderSymbol = categorySymbol(s.saved.category),
                            accent = accent,
                            style = PhotoCardStyle.Compact,
                            modifier = Modifier.testTag("open-now-${s.saved.id}"),
                        )
                    }
                }
            }
        }
        val timeline: @Composable () -> Unit = {
            SectionLabel("Tagesablauf", color = accent)
            DayTimeline(
                snapshot = snapshot,
                date = date,
                items = items,
                movements = state.movements,
                stay = stay,
                accent = accent,
                onOpenItem = { onOpenDay(date) },
                now = now,
                onOpenAttachment = onOpenAttachment,
                available = available,
                itemTagPrefix = "today-item-",
                emptyProgram = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EmptyHint("Für heute ist noch nichts geplant")
                        FilledTonalButton(onClick = { onOpenDay(date) }) { Text("Tag öffnen") }
                    }
                },
                modifier = Modifier.testTag("today-program"),
            )
        }
        if (isWideLayout()) {
            // Unfolded: the day's timeline on the left, stay and "Jetzt geöffnet" on the right.
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) { timeline() }
                Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) { stayBlock(); openBlock() }
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Stay first, then the timeline, then what is open now.
                stayBlock()
                timeline()
                openBlock()
            }
        }
    }
}

/** Small round navigation/transit buttons for the stay card (tags `link-<kind>` inside `today-stay-links`). */
@Composable
private fun StayActions(links: List<ExternalLink>, onOpen: (ExternalLink) -> Unit) {
    if (links.isEmpty()) return
    Row(Modifier.testTag("today-stay-links"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEach { l ->
            Surface(
                onClick = { onOpen(l) },
                modifier = Modifier.size(40.dp).testTag("link-${l.kind}"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (l.kind == LinkKind.TRANSIT) Icons.Filled.DirectionsTransit else Icons.Filled.Navigation,
                        contentDescription = l.label,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** Plain titled section; still used by the not yet redesigned detail screens. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.material3.HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
