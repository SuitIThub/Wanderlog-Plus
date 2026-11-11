package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Google search for linking: name (+ region) near a point; only hits with a Google id. */
typealias GoogleSearch = suspend (query: String, near: Pair<Double, Double>?) -> List<GeoPlace>

/** "365 m" below 1 km, else "1,2 km" (great-circle distance). */
fun distanceText(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
    val r = 6_371_000.0
    val dLat = Math.toRadians(toLat - fromLat)
    val dLng = Math.toRadians(toLng - fromLng)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * sin(dLng / 2).pow(2)
    val m = 2 * r * asin(sqrt(a))
    return if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.GERMAN, "%.1f km", m / 1000)
}

/**
 * Links [placeId] to Google [hit] (null: unlinks). The hit's address fills an empty address; with [takePosition] the
 * place moves to the hit's coordinates.
 */
fun googleLinkEdits(placeId: String, hit: GeoPlace?, takePosition: Boolean): (LocalEditor, TripSnapshot) -> Unit = { e, s ->
    val fields = mutableMapOf<String, JsonElement>("googlePlaceId" to JsonPrimitive(hit?.googlePlaceId.orEmpty()))
    if (hit != null) {
        if (s.places[placeId]?.address.isNullOrBlank() && hit.detail.isNotBlank()) fields["address"] = JsonPrimitive(hit.detail)
        if (takePosition) { fields["lat"] = JsonPrimitive(hit.lat); fields["lng"] = JsonPrimitive(hit.lng) }
    }
    e.set(EntityType.PLACE, placeId, fields)
}

/**
 * "Google-Eintrag": the linked entry (name and address, tap opens Google Maps) and "Ändern", or "Kein Google-Eintrag"
 * with "Suchen". [search] null (no server) hides both buttons. [initialQuery] fills the search field.
 */
@Composable
fun GoogleLinkRow(
    place: Place,
    initialQuery: String,
    search: GoogleSearch?,
    onLink: ((hit: GeoPlace?, takePosition: Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    /** One line ("verknüpft") instead of name and address, where the address already stands elsewhere. */
    compact: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val linked = place.googlePlaceId.isNotBlank()
    val uri = LocalUriHandler.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag("google-link"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (linked) Icons.Filled.Place else Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.padding(end = 10.dp))
            Column(
                Modifier.weight(1f).then(
                    if (linked) Modifier.clickable { runCatching { uri.openUri(googleMapsUrl(place)) } } else Modifier,
                ),
            ) {
                Text("Google-Eintrag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (linked && compact) {
                    Text("verknüpft – in Google Maps öffnen", style = MaterialTheme.typography.bodyMedium)
                } else if (linked) {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(place.address?.takeIf { it.isNotBlank() } ?: "Verknüpft", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Kein Google-Eintrag", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (search != null && onLink != null) {
                TextButton(onClick = { open = true }, modifier = Modifier.testTag("google-search")) { Text(if (linked) "Ändern" else "Suchen") }
            }
        }
    }
    if (open && search != null && onLink != null) {
        GoogleLinkSheet(
            initialQuery, place.takeIf { it.lat != 0.0 || it.lng != 0.0 }?.let { it.lat to it.lng }, search, linked,
            onPick = { h, take -> open = false; onLink(h, take) },
            onUnlink = { open = false; onLink(null, false) },
            onDismiss = { open = false },
        )
    }
}

/** Google Maps page of a linked place. */
fun googleMapsUrl(place: Place): String =
    "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(place.name, "UTF-8") +
        "&query_place_id=" + java.net.URLEncoder.encode(place.googlePlaceId, "UTF-8")

/** Search sheet: hits with address, distance to [near] and a "Lage übernehmen" switch; "Verknüpfung lösen" when [linked]. */
@Composable
fun GoogleLinkSheet(
    initialQuery: String,
    near: Pair<Double, Double>?,
    search: GoogleSearch,
    linked: Boolean,
    onPick: (GeoPlace, takePosition: Boolean) -> Unit,
    onUnlink: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var hits by remember { mutableStateOf<List<GeoPlace>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var takePosition by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        val q = query.trim()
        if (q.length < 2) return@LaunchedEffect
        hits = null
        hits = try {
            search(q, near).filter { !it.googlePlaceId.isNullOrBlank() }.also { failed = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            emptyList()
        }
    }
    EditSheet("Google-Eintrag suchen", onDismiss = onDismiss, modifier = Modifier.testTag("google-sheet")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f).testTag("google-query"))
            IconButton(onClick = { run++ }, modifier = Modifier.testTag("google-query-go")) { Icon(Icons.Filled.Search, contentDescription = "Suchen") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lage übernehmen", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = takePosition, onCheckedChange = { takePosition = it }, modifier = Modifier.testTag("google-take-position"))
        }
        val list = hits
        when {
            list == null -> CircularProgressIndicator(Modifier.padding(8.dp))
            failed -> Text("Die Google-Suche ist gerade nicht erreichbar.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text("Nichts gefunden.", style = MaterialTheme.typography.bodySmall)
        }
        list.orEmpty().forEach { h ->
            Column(
                Modifier.fillMaxWidth().clickable { onPick(h, takePosition) }.padding(vertical = 8.dp).testTag("google-hit-${h.googlePlaceId}"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(h.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val extra = listOfNotNull(h.detail.ifBlank { null }, near?.let { (la, ln) -> distanceText(la, ln, h.lat, h.lng) + " entfernt" })
                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        if (linked) {
            TextButton(onClick = onUnlink, modifier = Modifier.testTag("google-unlink")) { Text("Verknüpfung lösen") }
        }
    }
}
