package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.wanderlogplus.app.ui.dayCardLabel
import de.wanderlogplus.app.ui.design.PhotoOrGradient
import de.wanderlogplus.app.ui.design.dayHeroImage
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.query.DayView
import de.wanderlogplus.shared.query.Movement

/** Route of the day detail, nested in the Reise tab. */
const val DAY_ROUTE = "trip/day/{date}"
fun dayRoute(date: String) = "trip/day/$date"

@Composable
fun RegionChip(name: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(name, style = MaterialTheme.typography.labelLarge)
    }
}

/** Flights and trains departing on [date], by departure time (missing last). */
fun movementsOn(snapshot: TripSnapshot, date: String): List<Movement> =
    (snapshot.flights.filter { it.depart.date == date }.map { Movement.FlightMove(it) } +
        snapshot.trains.filter { it.depart.date == date }.map { Movement.TrainMove(it) })
        .sortedWith(compareBy(nullsLast()) { it.time })

/** Small symbol of a movement for compact hints ("3 geplant · 🚄"). */
fun Movement.symbol(): String = when (this) {
    is Movement.FlightMove -> "✈️"
    is Movement.TrainMove -> "🚄"
}

private data class DayCard(
    val view: DayView,
    val title: String,
    val subtitle: String,
    val accent: Color?,
    val image: String?,
    val symbol: String,
)

/** Days of the trip as cards; [today] ("yyyy-MM-dd", trip zone) is highlighted and scrolled into view. */
@Composable
fun TripScreen(snapshot: TripSnapshot, onOpenDay: (String) -> Unit, modifier: Modifier = Modifier, today: String? = null) {
    val cards = remember(snapshot) {
        val regionsById = snapshot.regions.associateBy { it.id }
        snapshot.queries()?.days().orEmpty().map { d ->
            val region = d.regionId?.let { regionsById[it] }
            val title = d.day?.title?.takeIf { it.isNotBlank() } ?: region?.name ?: if (d.isTravelDay) "Reisetag" else "Kein Titel"
            val planned = d.day?.let { snapshot.dayItemsFor(it.id).size } ?: 0
            val moves = movementsOn(snapshot, d.date).map { it.symbol() }.distinct()
            val subtitle = listOfNotNull(
                "Reisetag".takeIf { d.isTravelDay && title != "Reisetag" },
                "$planned geplant".takeIf { planned > 0 },
                moves.joinToString(" ").takeIf { it.isNotEmpty() },
            ).joinToString(" · ").ifEmpty { "Noch nichts geplant" }
            DayCard(d, title, subtitle, region?.let { regionColor(it.color) }, dayHeroImage(snapshot, d.date), moves.firstOrNull() ?: "📍")
        }
    }
    val todayIndex = cards.indexOfFirst { it.view.date == today }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (todayIndex - 1).coerceAtLeast(0))
    LazyColumn(
        modifier.fillMaxSize().testTag("trip-list"),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(cards, key = { it.view.date }) { c -> DayCardRow(c, isToday = c.view.date == today, onOpenDay) }
    }
}

@Composable
private fun DayCardRow(c: DayCard, isToday: Boolean, onOpenDay: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = if (isToday) scheme.primaryContainer else scheme.surfaceContainerLow
    val onContainer = if (isToday) scheme.onPrimaryContainer else scheme.onSurface
    val accent = c.accent ?: scheme.outline
    Card(
        onClick = { onOpenDay(c.view.date) },
        modifier = Modifier.fillMaxWidth().testTag("day-${c.view.date}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isToday) 3.dp else 1.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        dayCardLabel(c.view.date),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                        color = if (isToday) scheme.onPrimaryContainer else scheme.primary,
                        maxLines = 1,
                    )
                    if (isToday) {
                        Surface(shape = RoundedCornerShape(50), color = scheme.primary, contentColor = scheme.onPrimary) {
                            Text("Heute", Modifier.padding(horizontal = 9.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    c.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    c.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isToday) scheme.onPrimaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PhotoOrGradient(
                c.image, accent, "day-thumb-fallback",
                Modifier.padding(end = 10.dp, top = 10.dp, bottom = 10.dp).size(56.dp).clip(RoundedCornerShape(12.dp)),
                symbol = c.symbol, symbolSize = 22.sp,
            )
        }
    }
}
