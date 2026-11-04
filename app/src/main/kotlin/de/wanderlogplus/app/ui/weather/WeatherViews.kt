package de.wanderlogplus.app.ui.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.query.WeatherFit
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.TripWeather
import de.wanderlogplus.shared.sync.WeatherText
import kotlin.math.roundToInt

/** The trip weather from the server; null while unknown (tests, no server). */
val LocalTripWeather = compositionLocalOf<TripWeather?> { null }

fun weatherIcon(code: Int?): ImageVector = when (WeatherText.icon(code)) {
    "sun" -> Icons.Filled.WbSunny
    "partly" -> Icons.Filled.WbCloudy
    "rain" -> Icons.Filled.Umbrella
    "snow" -> Icons.Filled.AcUnit
    "storm" -> Icons.Filled.Thunderstorm
    else -> Icons.Filled.Cloud
}

@Composable
private fun iconTint(code: Int?): Color = when (WeatherText.icon(code)) {
    "sun" -> Color(0xFFF2A93B)
    "rain", "storm" -> Color(0xFF4A86C5)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Program places of [date] that are better in good weather (by name, for the rain hint). */
fun outdoorPlacesOn(snapshot: TripSnapshot, date: String): List<String> {
    val day = snapshot.queries()?.dayFor(date) ?: return emptyList()
    return snapshot.dayItemsFor(day.id).filter { it.kind == DayItemKind.PLACE }.mapNotNull { i ->
        val place = snapshot.places[i.placeId]
        val saved = snapshot.savedPlaces.firstOrNull { it.placeId == i.placeId }
        place?.name?.takeIf { WeatherFit.isWeatherSensitive(saved, place) }
    }.distinct()
}

/** Icon and highest temperature for a day row; a climate average is marked with "Ø" and has no icon. */
@Composable
fun WeatherBadge(d: DayWeather, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val hi = d.tempMax?.roundToInt() ?: return
    Row(modifier.testTag("weather-badge-${d.date}"), verticalAlignment = Alignment.CenterVertically) {
        if (d.isForecast) {
            Icon(weatherIcon(d.code), contentDescription = WeatherText.label(d.code), tint = iconTint(d.code), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(3.dp))
            Text("$hi°", style = MaterialTheme.typography.labelMedium, color = color)
        } else {
            Text("Ø $hi°", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/**
 * The weather of a day: icon, summary, source and – for a forecast – the hours (tap to unfold). On a rainy day with
 * outdoor places in [outdoor] it warns and offers [onReplan] (the chat).
 */
@Composable
fun DayWeatherCard(
    d: DayWeather,
    modifier: Modifier = Modifier,
    title: String? = null,
    outdoor: List<String> = emptyList(),
    onReplan: (() -> Unit)? = null,
    iconSize: Dp = 30.dp,
) {
    var open by rememberSaveable(d.date) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().testTag("day-weather")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (d.hours.isNotEmpty()) Modifier.clickable { open = !open } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (d.isForecast) {
                    Icon(weatherIcon(d.code), contentDescription = null, tint = iconTint(d.code), modifier = Modifier.size(iconSize))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    val head = listOfNotNull(
                        title,
                        if (d.isForecast) WeatherText.label(d.code) else "Typisches Wetter",
                        WeatherText.temps(d),
                    ).joinToString(" · ")
                    Text(head, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val sub = listOfNotNull(WeatherText.rain(d), WeatherText.sourceLabel(d)).joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (d.hours.isNotEmpty()) {
                    Icon(
                        if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (open) "Stunden ausblenden" else "Stunden zeigen",
                        modifier = Modifier.testTag("day-weather-toggle"),
                    )
                }
            }
            if (open && d.hours.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("day-weather-hours"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    d.hours.forEach { h ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.time.take(2), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            Icon(weatherIcon(h.code), contentDescription = WeatherText.label(h.code), tint = iconTint(h.code), modifier = Modifier.size(18.dp))
                            Text(h.temp?.let { "${it.roundToInt()}°" } ?: "–", style = MaterialTheme.typography.labelMedium)
                            Text(h.rainChance?.let { "$it %" } ?: "", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4A86C5))
                        }
                    }
                }
            }
            RainHint(d, outdoor, onReplan)
        }
    }
}

/** "Regen erwartet – … eher etwas für gutes Wetter" with "Umplanen"; nothing on a dry day or without outdoor places. */
@Composable
fun RainHint(d: DayWeather, outdoor: List<String>, onReplan: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (outdoor.isEmpty() || !WeatherText.isRainy(d)) return
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer, modifier = modifier.fillMaxWidth().testTag("day-rain-hint")) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Umbrella, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Regen erwartet – " + outdoor.joinToString(", ") + (if (outdoor.size == 1) " ist" else " sind") + " eher etwas für gutes Wetter.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (onReplan != null) {
                TextButton(onClick = onReplan, modifier = Modifier.testTag("day-rain-replan")) { Text("Umplanen") }
            }
        }
    }
}

/** Compact weather for a dashboard tile: icon, temperatures, rain and – for tomorrow – one short line. */
@Composable
fun WeatherTileContent(today: DayWeather?, tomorrow: DayWeather?) {
    val scheme = MaterialTheme.colorScheme
    if (today != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (today.isForecast) {
                Icon(weatherIcon(today.code), contentDescription = WeatherText.label(today.code), tint = iconTint(today.code), modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column {
                Text(WeatherText.temps(today) ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(if (today.isForecast) WeatherText.label(today.code) else "Typisch", WeatherText.rain(today)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 2,
                )
            }
        }
    }
    if (tomorrow != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("tomorrow-weather")) {
            Text("Morgen ", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            if (tomorrow.isForecast) Icon(weatherIcon(tomorrow.code), contentDescription = WeatherText.label(tomorrow.code), tint = iconTint(tomorrow.code), modifier = Modifier.size(14.dp))
            Text(
                " " + listOfNotNull(WeatherText.temps(tomorrow), tomorrow.rainChance?.let { "$it %" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}
