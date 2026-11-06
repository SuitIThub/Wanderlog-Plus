package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.fillMaxHeight
import de.wanderlogplus.app.ui.design.isWideLayout
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LocalMapsEnabled
import de.wanderlogplus.app.maps.SearchMapView
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.TripSnapshot
import kotlinx.coroutines.launch

const val DAY_SEARCH_ROUTE = "trip/day/{date}/search/{position}"

fun daySearchRoute(date: String, position: String) = "trip/day/$date/search/${android.net.Uri.encode(position)}"

/**
 * Where the search map starts: the place before the insert position, else the first place of the day with coordinates,
 * else the centre of the day's region's places; null when nothing is known.
 */
fun searchStart(snapshot: TripSnapshot, date: String, afterItemId: String?): LatLngD? {
    fun coords(placeId: String?): LatLngD? = placeId?.let { snapshot.places[it] }?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { LatLngD(it.lat, it.lng) }
    val q = snapshot.queries()
    val items = q?.dayFor(date)?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
    afterItemId?.let { id -> items.firstOrNull { it.id == id }?.let { coords(it.placeId) } }?.let { return it }
    items.firstNotNullOfOrNull { coords(it.placeId) }?.let { return it }
    val region = q?.regionFor(date) ?: return null
    val pts = snapshot.savedPlaces.filter { it.regionId == region }.mapNotNull { coords(it.placeId) }
    if (pts.isEmpty()) return null
    return LatLngD(pts.map { it.lat }.average(), pts.map { it.lng }.average())
}

/**
 * "Ort suchen": search field, map and hit list. The search prefers the visible map area (see [PlaceSearchModel]); a long
 * press on the map picks any point. The chosen place goes to the day with "Zum Tag hinzufügen" ([onAdd]).
 * Tags: `search-field`, `search-submit`, `search-area`, `search-hit-<i>`, `search-add`, `search-status`.
 */
@Composable
fun PlaceSearchScreen(
    model: PlaceSearchModel,
    start: LatLngD?,
    onAdd: (GeoPlace) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    addLabel: String = "Zum Tag hinzufügen",
) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); scope.launch { model.submit() }; Unit }
    Column(modifier.fillMaxSize().imePadding().testTag("place-search-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            OutlinedTextField(
                value = model.query,
                onValueChange = { model.query = it },
                placeholder = { Text("Ort, Restaurant, Bahnhof …") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f).testTag("search-field"),
            )
            IconButton(onClick = submit, modifier = Modifier.testTag("search-submit")) {
                Icon(Icons.Filled.Search, contentDescription = "Suchen")
            }
        }
        val wide = isWideLayout()
        val map: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                if (LocalMapsEnabled.current) {
                    SearchMapView(
                        start = start,
                        results = model.results,
                        selected = model.selected,
                        fitRequest = model.fitRequest,
                        onVisible = { model.visible = it },
                        onSelect = { model.selected = it },
                        onLongPress = { lat, lng -> scope.launch { model.pick(lat, lng) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "Karte nicht verfügbar",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (model.canSearchArea) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { model.searchArea() } },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        text = { Text("In diesem Bereich suchen") },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp).testTag("search-area"),
                    )
                }
            }
        }
        val status: @Composable () -> Unit = {
            statusText(model.status, model.results.size)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("search-status"),
                )
            }
            if (!model.viaGoogle && model.status != SearchStatus.IDLE && model.status != SearchStatus.LOADING) {
                Text(
                    "Suche ohne Google (OpenStreetMap)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("search-no-google"),
                )
            }
        }
        val hits: @Composable (Modifier) -> Unit = { m ->
            LazyColumn(m) {
                itemsIndexed(model.results) { i, r ->
                    val isSelected = model.selected == r
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { model.selected = r }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("search-hit-$i"),
                    ) {
                        Text(
                            r.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        val sub = listOfNotNull(categoryLabel(r.category).takeIf { r.osmKey != null }, r.detail.ifBlank { null }).joinToString(" · ")
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        val chosen: @Composable () -> Unit = {
            model.selected?.let { sel ->
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 6.dp,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(sel.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (sel.detail.isNotBlank()) {
                            Text(sel.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(onClick = { onAdd(sel) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("search-add")) { Text(addLabel) }
                    }
                }
            }
        }
        if (wide) {
            // Unfolded: map on the left, hits and the chosen place on the right.
            Row(Modifier.fillMaxWidth().weight(1f)) {
                map(Modifier.weight(0.6f).fillMaxHeight())
                VerticalDivider()
                Column(Modifier.weight(0.4f).fillMaxHeight()) {
                    status()
                    hits(Modifier.fillMaxWidth().weight(1f))
                    chosen()
                }
            }
        } else {
            map(Modifier.fillMaxWidth().weight(0.55f))
            status()
            hits(Modifier.fillMaxWidth().weight(0.45f))
            chosen()
        }
    }
}

/** A [PlaceSearchModel] kept for the screen's lifetime. */
@Composable
fun rememberPlaceSearchModel(search: FinderSearch, lookup: PointLookup, home: LatLngD?): PlaceSearchModel =
    remember { PlaceSearchModel(search, lookup, home?.let { it.lat to it.lng }) }
