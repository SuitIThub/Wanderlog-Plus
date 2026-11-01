package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot

/** Route of the day detail, nested in the Reise tab. */
const val DAY_ROUTE = "trip/day/{date}"
fun dayRoute(date: String) = "trip/day/$date"

@Composable
fun RegionChip(name: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(name, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TripScreen(snapshot: TripSnapshot, onOpenDay: (String) -> Unit, modifier: Modifier = Modifier) {
    val days = snapshot.queries()?.days().orEmpty()
    val regionsById = snapshot.regions.associateBy { it.id }
    LazyColumn(modifier.fillMaxSize().testTag("trip-list")) {
        items(days, key = { it.date }) { d ->
            Column(
                Modifier.fillMaxWidth().clickable { onOpenDay(d.date) }.padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("day-${d.date}"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel(d.date), style = MaterialTheme.typography.titleMedium)
                    d.day?.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    d.regionId?.let { regionsById[it] }?.let { RegionChip(it.name, regionColor(it.color)) }
                    if (d.isTravelDay) {
                        Text("Reisetag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                d.stay?.let { snapshot.places[it.placeId]?.name }?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}
