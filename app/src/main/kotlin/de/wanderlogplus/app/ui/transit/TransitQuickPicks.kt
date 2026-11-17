package de.wanderlogplus.app.ui.transit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.model.FavoriteEnd
import de.wanderlogplus.shared.model.TransitFavorite

/** Opens the free search for a favourite and starts it ("Schnell hin" on Heute); null hides the card. */
val LocalQuickRoute = compositionLocalOf<((TransitFavorite) -> Unit)?> { null }

/** ☆/★ toggle; [tag] is its test tag. */
@Composable
fun StarButton(starred: Boolean, onToggle: () -> Unit, tag: String, what: String) {
    IconButton(onClick = onToggle, modifier = Modifier.testTag(tag)) {
        Icon(
            if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (starred) "$what nicht mehr merken" else "$what als Favorit merken",
            tint = if (starred) Color(0xFFE0A800) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Below Start und Ziel of the free search: the current route with its ☆ (only the route), the favourites (a route
 * fills both fields, a goal fills „Nach“) and this phone's last searches (tap fills both, ☆ marks the route).
 * Tags: `quick-route-star`, `fav-<id>`, `history-<i>`, `history-<i>-star`.
 */
@Composable
fun QuickPicks(
    route: Pair<FavoriteEnd, FavoriteEnd>?,
    routeStarred: Boolean,
    onStarRoute: () -> Unit,
    favorites: List<TransitFavorite>,
    onPickFavorite: (TransitFavorite) -> Unit,
    history: List<HistoryRoute>,
    historyStarred: (HistoryRoute) -> Boolean,
    onStarHistory: (HistoryRoute) -> Unit,
    onPickHistory: (HistoryRoute) -> Unit,
    accent: Color,
) {
    if (route != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Route ${route.first.name} → ${route.second.name}",
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            StarButton(routeStarred, onStarRoute, "quick-route-star", "Route")
        }
    }
    if (favorites.isNotEmpty()) {
        SectionLabel("Favoriten", color = accent)
        favorites.forEach { f ->
            PickRow(f.label, route = f.kind == TransitFavorite.KIND_ROUTE, onClick = { onPickFavorite(f) }, tag = "fav-${f.id}")
        }
    }
    if (history.isNotEmpty()) {
        SectionLabel("Zuletzt gesucht", color = accent)
        history.forEachIndexed { i, h ->
            PickRow("${h.from.name} → ${h.to.name}", route = true, onClick = { onPickHistory(h) }, tag = "history-$i") {
                StarButton(historyStarred(h), { onStarHistory(h) }, "history-$i-star", "Route")
            }
        }
    }
}

@Composable
private fun PickRow(label: String, route: Boolean, onClick: () -> Unit, tag: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (route) Icons.Filled.DirectionsTransit else Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        trailing?.invoke()
    }
}

/** "Schnell hin" on Heute: the favourites of the day's region; a tap searches from here (goal) or the route, now. */
@Composable
fun QuickRoutesCard(favorites: List<TransitFavorite>, accent: Color, onOpen: (TransitFavorite) -> Unit) {
    if (favorites.isEmpty()) return
    SurfaceCard(Modifier.fillMaxWidth().testTag("quick-routes")) {
        Column(Modifier.padding(16.dp)) {
            Text("Schnell hin", style = MaterialTheme.typography.titleMedium)
            Text("Verbindung ab jetzt suchen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            favorites.forEach { f ->
                PickRow(
                    if (f.kind == TransitFavorite.KIND_ROUTE) f.label else "Von hier → ${f.label}",
                    route = f.kind == TransitFavorite.KIND_ROUTE, onClick = { onOpen(f) }, tag = "quick-route-${f.id}",
                )
            }
        }
    }
}
