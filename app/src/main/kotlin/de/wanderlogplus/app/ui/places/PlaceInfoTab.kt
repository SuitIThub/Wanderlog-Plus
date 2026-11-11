package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.openingHoursLines
import de.wanderlogplus.shared.model.Place
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Index into [openingHoursLines] (0 = Monday) for the ISO date [today]; null when absent or unparsable. */
internal fun weekdayIndex(today: String?): Int? =
    try { today?.let { LocalDate.parse(it).dayOfWeek.value - 1 } } catch (_: DateTimeParseException) { null }

/** Info tab: description, opening hours (today highlighted), duration and address. */
@Composable
internal fun PlaceInfoTab(place: Place?, accent: Color, today: String?, saved: de.wanderlogplus.shared.model.SavedPlace? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        saved?.worth?.let { w ->
            SectionLabel("Einschätzung", color = accent)
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 4.dp).testTag("place-worth")) {
                de.wanderlogplus.app.ui.design.Badge(de.wanderlogplus.app.ui.design.BadgeSpec(de.wanderlogplus.app.ui.reels.worthLabel(w), de.wanderlogplus.app.ui.reels.worthTone(w)))
            }
            if (saved.worthReason.isNotBlank()) Text(de.wanderlogplus.app.ui.euroed(saved.worthReason), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        place?.description?.takeIf { it.isNotBlank() }?.let {
            SectionLabel("Beschreibung", color = accent)
            Text(de.wanderlogplus.app.ui.euroed(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }

        SectionLabel("Öffnungszeiten", color = accent)
        HoursTable(openingHoursLines(place?.openingPeriods.orEmpty()), weekdayIndex(today))

        val duration = place?.let(::durationText)
        val address = place?.address?.takeIf { it.isNotBlank() }
        if (duration != null || address != null) {
            SectionLabel("Details", color = accent)
            InfoCard {
                duration?.let { FactRow("Dauer", it) }
                address?.let { FactRow("Adresse", it) }
            }
        }
        if (place == null) EmptyHint("Keine Ortsdaten", Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

/** Compact weekday table; the row at [todayIndex] gets a tinted background, bold text and a "heute" badge. */
@Composable
private fun HoursTable(lines: List<Pair<String, String>>, todayIndex: Int?) {
    // The same every day: one line instead of seven ("Täglich 09:30–18:00", "Öffnungszeiten unbekannt").
    val same = lines.map { it.second }.distinct().singleOrNull()
    if (same != null && lines.size == 7) {
        val unknown = same == "unbekannt"
        InfoCard {
            Text(
                if (unknown) "Öffnungszeiten unbekannt" else "Täglich $same",
                style = MaterialTheme.typography.bodyMedium,
                color = if (unknown) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(if (unknown) "hours-unknown" else "hours-daily"),
            )
        }
        return
    }
    InfoCard {
        lines.forEachIndexed { i, (day, text) ->
            val isToday = i == todayIndex
            val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).testTag("hours-$day")
            Surface(
                rowModifier,
                shape = RoundedCornerShape(12.dp),
                color = if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        day,
                        Modifier.width(32.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(
                        text,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isToday) TodayPill()
                }
            }
        }
    }
}

@Composable
private fun TodayPill() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Text("heute", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
