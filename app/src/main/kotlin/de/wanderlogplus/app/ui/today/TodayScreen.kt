package de.wanderlogplus.app.ui.today

import de.wanderlogplus.shared.sync.EntityType

import de.wanderlogplus.app.ui.guides.guideWorld
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.ui.EmptyState
import de.wanderlogplus.app.ui.HeroTitle
import de.wanderlogplus.app.ui.dayKicker
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.LocalOpenChat
import de.wanderlogplus.app.ui.design.dayHeroImage
import de.wanderlogplus.app.ui.design.heroGradient
import de.wanderlogplus.app.ui.design.isWideLayout
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.stayLinks
import de.wanderlogplus.app.ui.weather.DayWeatherCard
import de.wanderlogplus.app.ui.weather.LocalTripWeather
import de.wanderlogplus.app.ui.weather.RainHint
import de.wanderlogplus.app.ui.weather.WeatherTileContent
import de.wanderlogplus.app.ui.weather.outdoorPlacesOn
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.DayNow
import de.wanderlogplus.shared.query.DayStep
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.TodayQueries
import de.wanderlogplus.shared.query.TodayState
import de.wanderlogplus.shared.query.isOpenAt
import de.wanderlogplus.shared.query.sortSuggestions
import de.wanderlogplus.shared.sync.DayWeather
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
    /** Tapped ticket chip of the next train or flight. */
    onOpenAttachment: (Attachment) -> Unit = {},
    /** Null hides the stay link buttons and the route button. */
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    /** Whether a file is stored locally (by hash); unavailable tickets are marked. */
    available: (hash: String) -> Boolean = { true },
    /** Opens a place (from "Fristen"); null: the rows are not tappable. */
    onOpenPlace: ((savedPlaceId: String) -> Unit)? = null,
    /** Opens a stay, train or flight (from "Fristen"); null: those rows are not tappable. */
    onOpenLogistics: ((EntityType, String) -> Unit)? = null,
    /** "Verpasst? Spätere Fahrt" on the next train; null hides it. */
    onReplaceTrain: ((String) -> Unit)? = null,
) {
    val openDeadline: ((de.wanderlogplus.shared.query.DeadlineTarget, String) -> Unit)? =
        if (onOpenPlace == null && onOpenLogistics == null) null else { t, id ->
            when (t) {
                de.wanderlogplus.shared.query.DeadlineTarget.PLACE -> onOpenPlace?.invoke(id)
                de.wanderlogplus.shared.query.DeadlineTarget.STAY -> onOpenLogistics?.invoke(EntityType.STAY, id)
                de.wanderlogplus.shared.query.DeadlineTarget.TRAIN -> onOpenLogistics?.invoke(EntityType.TRAIN, id)
                de.wanderlogplus.shared.query.DeadlineTarget.FLIGHT -> onOpenLogistics?.invoke(EntityType.FLIGHT, id)
            }
        }
    val trip = snapshot.trip
    val queries = remember(snapshot) { snapshot.queries() }
    val state = remember(snapshot, now.toLocalDate()) {
        if (trip == null || queries == null) TodayState.Unknown
        else TodayQueries(queries, trip, snapshot.stays, snapshot.flights, snapshot.trains).state(now.toLocalDate())
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalReplaceTrain provides onReplaceTrain) {
    when (state) {
        TodayState.Unknown -> EmptyState(onSyncNow, modifier)
        is TodayState.Before -> Before(snapshot, state, now, onOpenAttachment, available, openDeadline, modifier)
        TodayState.After -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Willkommen zurück", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onOpenTrip) { Text("Zur Reise") }
        }
        is TodayState.During -> During(snapshot, state, now, onOpenDay, onOpenAttachment, onOpenLink, available, openDeadline, modifier)
    }
    }
}

/** "Verpasst? Spätere Fahrt" for the next train card; null hides it. */
internal val LocalReplaceTrain = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Before the trip: countdown, then the first departure (once close), the weather of the first day and the deadlines. */
@Composable
private fun Before(
    snapshot: TripSnapshot,
    state: TodayState.Before,
    now: LocalDateTime,
    onOpenAttachment: (Attachment) -> Unit,
    available: (String) -> Boolean,
    onOpenPlace: ((de.wanderlogplus.shared.query.DeadlineTarget, String) -> Unit)?,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val today = now.toLocalDate().toString()
    val move = remember(snapshot, now) { upcomingMove(snapshot, now) }
    val deadlines = remember(snapshot, today) { deadlineRows(snapshot, today) }
    val ideaDeadlines = remember(snapshot, today) { ideaDeadlineRows(snapshot, today) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).background(heroGradient(accent))) {
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
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            move?.let { NextTripWidget(snapshot, it, now, accent, onOpenAttachment, available, onReplaceTrain = LocalReplaceTrain.current) }
            // Within the forecast range: the weather of the first trip day.
            snapshot.trip?.startDate?.let { start -> LocalTripWeather.current?.forDate(start) }?.takeIf { it.isForecast }?.let { w ->
                Widget("Wetter zum Start", "start-weather", accent = accent) {
                    DayWeatherCard(w, title = w.place.takeIf { it.isNotBlank() })
                }
            }
            if (deadlines.isNotEmpty() || ideaDeadlines.isNotEmpty()) DeadlinesWidget(deadlines, accent, onOpenPlace, ideas = ideaDeadlines)
            de.wanderlogplus.app.ui.places.PlanDoubtCard(
                remember(snapshot, now.toLocalDate()) { de.wanderlogplus.app.ui.places.planDoubts(snapshot, now.toLocalDate().toString()) },
                onOpenPlace?.let { open -> { id: String -> open(de.wanderlogplus.shared.query.DeadlineTarget.PLACE, id) } },
            )
        }
    }
}

/**
 * During the trip, a dashboard of widgets that only appear with content: the day (now, next and the compact plan),
 * the next train or flight, weather and stay side by side, deadlines and what is open now.
 */
@Composable
private fun During(
    snapshot: TripSnapshot,
    state: TodayState.During,
    now: LocalDateTime,
    onOpenDay: (String) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onOpenLink: ((ExternalLink) -> Unit)?,
    available: (String) -> Boolean,
    onOpenPlace: ((de.wanderlogplus.shared.query.DeadlineTarget, String) -> Unit)?,
    modifier: Modifier,
) {
    val view = state.day
    val date = view.date
    val region = view.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
    val dayTitle = view.day?.title?.takeIf { it.isNotBlank() }
    val stay = view.stay
    val stayPlace = stay?.let { snapshot.places[it.placeId] }
    val heroImage = remember(snapshot, date) { dayHeroImage(snapshot, date) }
    val nowMin = now.hour * 60 + now.minute
    val dayNow = remember(snapshot, view.day, state.movements, stayPlace, nowMin) {
        val items = view.day?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        DayNow.layout(items, snapshot.places, snapshot.savedPlaces, state.movements, stayPlace, nowMin)
    }
    val move = remember(snapshot, now) { upcomingMove(snapshot, now) }
    val deadlines = remember(snapshot, date) { deadlineRows(snapshot, date) }
    val ideaDeadlines = remember(snapshot, date) { ideaDeadlineRows(snapshot, date) }
    val openNow = remember(snapshot, date, now, dayNow, stayPlace) {
        val dow = now.dayOfWeek.value % 7 // 0 = Sunday
        val time = now.format(HH_MM)
        val planned = view.day?.let { d -> snapshot.dayItemsFor(d.id).mapNotNull { it.placeId }.toSet() }.orEmpty()
        val all = snapshot.queries()?.openNowCandidatesFor(date).orEmpty().map { Suggestion(it, snapshot.places[it.placeId]) }
        // Where you are: the current or next stop, else tonight's stay.
        val anchor = ((dayNow.current ?: dayNow.next) as? DayStep.Visit)?.place?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) } ?: stayPlace
        val open = sortSuggestions(all.filter { it.saved.placeId !in planned && isOpenAt(it.place?.openingPeriods.orEmpty(), dow, time) == true })
        openNowRows(open, anchor, dow, nowMin, MAX_OPEN_NOW)
    }
    val weather = LocalTripWeather.current
    val todayWeather = weather?.forDate(date)
    val tomorrowWeather = weather?.forDate(now.toLocalDate().plusDays(1).toString())
    val outdoor = remember(snapshot, date) { outdoorPlacesOn(snapshot, date) }
    val openChat = LocalOpenChat.current
    val openGuide = de.wanderlogplus.app.ui.guides.LocalOpenGuide.current
    val openGuides = de.wanderlogplus.app.ui.guides.LocalOpenGuides.current
    val dayGuides = remember(snapshot, view.day, state.movements, date, nowMin) {
        val items = view.day?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
        val flow = de.wanderlogplus.shared.query.DayFlow.build(date, items, state.movements, snapshot.stays, snapshot.savedPlaces, snapshot.places, snapshot.regions)
        de.wanderlogplus.shared.guide.GuideMatch.forDay(date, flow, snapshot.guideWorld(), nowMin)
    }
    val suggestedGuides = remember(snapshot, date) {
        de.wanderlogplus.shared.guide.GuideSuggestions.openOn(date, snapshot.guideWorld(), snapshot.guidePrefs).size
    }

    val program: @Composable () -> Unit = {
        NowNextWidget(snapshot, date, dayNow, now, stayPlace, accent, onOpenDay, onOpenLink, onOpenAttachment = onOpenAttachment, available = available)
    }
    // The day card already shows a trip that is now or next (with its tickets): no second widget for it.
    val focusMove = ((dayNow.current ?: dayNow.next) as? de.wanderlogplus.shared.query.DayStep.Move)?.movement
    val side: @Composable () -> Unit = {
        move?.takeIf { it.movement != focusMove }?.let { NextTripWidget(snapshot, it, now, accent, onOpenAttachment, available, onReplaceTrain = LocalReplaceTrain.current) }
        if (openGuide != null && (dayGuides.isNotEmpty() || (suggestedGuides > 0 && openGuides != null))) {
            TodayGuidesWidget(dayGuides, suggestedGuides, accent, openGuide, openGuides)
        }
        WeatherAndStay(date, todayWeather, tomorrowWeather, stay, stayPlace, accent, onOpenLink)
        todayWeather?.let { RainHint(it, outdoor, openChat) }
        if (deadlines.isNotEmpty() || ideaDeadlines.isNotEmpty()) DeadlinesWidget(deadlines, accent, onOpenPlace, ideas = ideaDeadlines)
        de.wanderlogplus.app.ui.places.PlanDoubtCard(
            remember(snapshot, now.toLocalDate()) { de.wanderlogplus.app.ui.places.planDoubts(snapshot, now.toLocalDate().toString()) },
            onOpenPlace?.let { open -> { id: String -> open(de.wanderlogplus.shared.query.DeadlineTarget.PLACE, id) } },
        )
        if (openNow.isNotEmpty()) OpenNowWidget(openNow, accent)
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().clickable { onOpenDay(date) }.testTag("today-header")) {
            HeroHeader(
                title = dayTitle ?: region?.name ?: dayLabel(date),
                kicker = dayKicker(date, snapshot.trip?.startDate, snapshot.trip?.endDate),
                imageUrl = heroImage,
                accent = accent,
                height = 120.dp,
                chips = if (dayTitle != null && region != null) listOf(region.name) else emptyList(),
            )
        }
        if (isWideLayout()) {
            // Unfolded: the day on the left, the other widgets on the right.
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(12.dp)) { program() }
                Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(12.dp)) { side() }
            }
        } else {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                program()
                side()
            }
        }
    }
}

/** Weather and stay as two tiles side by side (one takes the full width when the other has nothing). */
@Composable
private fun WeatherAndStay(
    date: String,
    today: DayWeather?,
    tomorrow: DayWeather?,
    stay: Stay?,
    stayPlace: Place?,
    accent: Color,
    onOpenLink: ((ExternalLink) -> Unit)?,
) {
    var expanded by rememberSaveable(date) { mutableStateOf(false) }
    val hasWeather = today != null || tomorrow != null
    if (!hasWeather && stay == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasWeather) {
                Widget(
                    "Wetter", "today-weather", Modifier.weight(1f).fillMaxHeight(), accent,
                    onClick = today?.takeIf { it.hours.isNotEmpty() }?.let { { expanded = !expanded } },
                ) { WeatherTileContent(today, tomorrow) }
            }
            if (stay != null) {
                Widget("Unterkunft", "today-stay", Modifier.weight(1f).fillMaxHeight(), accent) {
                    Text(
                        stayPlace?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val hint = when (date) {
                        stay.checkIn -> "Check-in heute"
                        stay.checkOut -> "Check-out heute"
                        else -> "Check-out ${dayLabel(stay.checkOut)}"
                    }
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (stayPlace != null && onOpenLink != null) StayActions(stayLinks(stayPlace), onOpenLink)
                }
            }
        }
        if (expanded && today != null) DayWeatherCard(today, Modifier.testTag("today-weather-detail"), title = "Heute")
    }
}

/** Small round navigation/transit buttons for the stay tile (tags `link-<kind>` inside `today-stay-links`). */
@Composable
private fun StayActions(links: List<ExternalLink>, onOpen: (ExternalLink) -> Unit) {
    if (links.isEmpty()) return
    Row(Modifier.testTag("today-stay-links"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEach { l ->
            Surface(
                onClick = { onOpen(l) },
                modifier = Modifier.size(36.dp).testTag("link-${l.kind}"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (l.kind == LinkKind.TRANSIT) Icons.Filled.DirectionsTransit else Icons.Filled.Navigation,
                        contentDescription = l.label,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.material3.HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}
