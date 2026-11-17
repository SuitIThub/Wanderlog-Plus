package de.wanderlogplus.app.ui.transit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import de.wanderlogplus.client.PlaceFinder
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.FavoriteEnd
import de.wanderlogplus.shared.model.TransitFavorite
import de.wanderlogplus.shared.sync.TransitResult

/**
 * The free connection search: [TransitScreen] with ☆ for Von/Nach (goals) and the route, the favourites and this
 * phone's [history]; "Übernehmen" asks where to save. With [favId] ("Schnell hin" on Heute) the favourite is filled in
 * once and searched right away – a goal from the current position ([locate]), a route as it is.
 */
@Composable
fun FreeTransitSearch(
    snapshot: TripSnapshot,
    tvm: TransitViewModel,
    history: TransitHistory,
    favId: String?,
    finder: PlaceFinder?,
    locate: () -> Unit,
    onAddFavorite: (TransitFavorite) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onSaveLogistics: (TransitResult) -> Unit,
    onSaveDay: (date: String, key: String, TransitResult) -> Unit,
    onBack: () -> Unit,
) {
    val state by tvm.state.collectAsState()
    var past by remember { mutableStateOf(history.read()) }
    var saving by remember { mutableStateOf<TransitResult?>(null) }
    val f = state.form
    val fromEnd = formEnd(f.fromPoint, f.fromStation)
    val toEnd = formEnd(f.toPoint, f.toStation)

    fun apply(end: FavoriteEnd, from: Boolean) {
        val la = end.lat
        val ln = end.lng
        if (la != null && ln != null) {
            val hit = EndpointHit(end.name, "", la, ln, fromTrip = false)
            if (from) tvm.pickFrom(hit) else tvm.pickTo(hit)
            if (end.station.isNotBlank()) tvm.edit { if (from) it.copy(fromStation = end.station) else it.copy(toStation = end.station) }
        } else {
            if (from) tvm.clearFrom() else tvm.clearTo()
            val name = end.station.ifBlank { end.name }
            tvm.edit { if (from) it.copy(fromStation = name) else it.copy(toStation = name) }
        }
    }

    fun search() {
        val form = tvm.state.value.form
        val a = formEnd(form.fromPoint, form.fromStation)
        val b = formEnd(form.toPoint, form.toStation)
        if (a != null && b != null) {
            history.add(HistoryRoute(a, b))
            past = history.read()
        }
        tvm.search()
    }

    fun toggle(existing: TransitFavorite?, make: () -> TransitFavorite) {
        if (existing != null) onRemoveFavorite(existing.id) else onAddFavorite(make())
    }

    // "Schnell hin": fill the favourite in once; a route searches at once, a goal once the position is there.
    var started by rememberSaveable(favId) { mutableStateOf(false) }
    var waitForHere by rememberSaveable(favId) { mutableStateOf(false) }
    LaunchedEffect(favId, snapshot) {
        val fav = favId?.let { id -> snapshot.transitFavorites.firstOrNull { it.id == id } } ?: return@LaunchedEffect
        if (started) return@LaunchedEffect
        started = true
        apply(fav.to, from = false)
        val from = fav.from
        if (fav.kind == TransitFavorite.KIND_ROUTE && from != null) {
            apply(from, from = true)
            search()
        } else {
            waitForHere = true
            locate()
        }
    }
    LaunchedEffect(f.fromPoint, waitForHere) {
        if (waitForHere && f.fromPoint != null) {
            waitForHere = false
            search()
        }
    }

    TransitScreen(
        title = "Ohne Bezug – gespeichert wird nur, was du übernimmst",
        heading = "Verbindung suchen",
        state = state,
        accent = MaterialTheme.colorScheme.primary,
        chosen = null,
        onEdit = tvm::edit,
        onSearch = { search() },
        onChoose = { r -> saving = r },
        onClear = {},
        onBack = onBack,
        suggest = withHistory(transitSuggest(snapshot, f.fromPoint ?: f.toPoint, finder), past),
        onPickFrom = tvm::pickFrom, onPickTo = tvm::pickTo, onClearFrom = tvm::clearFrom, onClearTo = tvm::clearTo,
        onEarlier = tvm::earlier, onLater = tvm::later,
        onLocateFrom = locate,
        fromStarred = snapshot.placeFavorite(fromEnd) != null,
        onStarFrom = { fromEnd?.let { e -> toggle(snapshot.placeFavorite(e)) { TransitFavorite("", to = e) } } },
        toStarred = snapshot.placeFavorite(toEnd) != null,
        onStarTo = { toEnd?.let { e -> toggle(snapshot.placeFavorite(e)) { TransitFavorite("", to = e) } } },
        quickPicks = {
            QuickPicks(
                route = if (fromEnd != null && toEnd != null) fromEnd to toEnd else null,
                routeStarred = snapshot.routeFavorite(fromEnd, toEnd) != null,
                onStarRoute = {
                    if (fromEnd != null && toEnd != null) {
                        toggle(snapshot.routeFavorite(fromEnd, toEnd)) { TransitFavorite("", TransitFavorite.KIND_ROUTE, to = toEnd, from = fromEnd) }
                    }
                },
                favorites = snapshot.transitFavorites,
                onPickFavorite = { fav ->
                    apply(fav.to, from = false)
                    fav.from?.takeIf { fav.kind == TransitFavorite.KIND_ROUTE }?.let { apply(it, from = true) }
                },
                history = past,
                historyStarred = { h -> snapshot.routeFavorite(h.from, h.to) != null },
                onStarHistory = { h -> toggle(snapshot.routeFavorite(h.from, h.to)) { TransitFavorite("", TransitFavorite.KIND_ROUTE, to = h.to, from = h.from) } },
                onPickHistory = { h -> apply(h.from, from = true); apply(h.to, from = false) },
                accent = MaterialTheme.colorScheme.primary,
            )
        },
    )
    saving?.let { r ->
        SaveConnectionDialog(
            snapshot = snapshot,
            date = state.form.date,
            onLogistics = { saving = null; onSaveLogistics(r) },
            onDay = { d, key -> saving = null; onSaveDay(d, key, r) },
            onDismiss = { saving = null },
        )
    }
}
