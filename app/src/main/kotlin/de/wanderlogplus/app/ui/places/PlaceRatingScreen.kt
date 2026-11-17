package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Votes

const val PLACE_RATING_ROUTE = "orte/bewerten"

/**
 * Bewerten: one unrated place after the other as a compact card, with 👎 / Neutral / 👍 / 👍👍 and „Überspringen“.
 * The queue is fixed when the mode starts; places deleted or rated elsewhere meanwhile are passed over.
 */
@Composable
fun PlaceRatingScreen(
    snapshot: TripSnapshot,
    me: String,
    onVote: (savedPlaceId: String, value: Int) -> Unit,
    onOpenPlace: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the full map of a place (with its research spots). */
    onOpenMap: (savedPlaceId: String) -> Unit = {},
) {
    val queue = rememberSaveable { ratingQueue(snapshot, me) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val position = (index until queue.size).firstOrNull { i ->
        val id = queue[i]
        snapshot.savedPlaces.any { it.id == id } && !isRated(snapshot, id, me)
    }
    val current = position?.let { p -> snapshot.savedPlaces.first { it.id == queue[p] } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Orte bewerten", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (position != null) {
                Text("${position + 1} / ${queue.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("rate-progress"))
            }
            IconButton(onClick = onDone, modifier = Modifier.testTag("rate-close")) { Icon(Icons.Filled.Close, contentDescription = "Beenden") }
        }
        if (current == null || position == null) {
            Column(Modifier.fillMaxSize().padding(24.dp).testTag("rate-done"), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Alles bewertet 🎉", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onDone) { Text("Zurück zu den Orten") }
            }
            return@Column
        }
        RatingCard(snapshot, current, me, onOpenPlace, onOpenMap, Modifier.weight(1f))
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(Votes.DISLIKE, "👎", "rate-dislike"), Triple(0, "Neutral", "rate-neutral"), Triple(Votes.LIKE, "👍", "rate-like"), Triple(Votes.DOUBLE, "👍👍", "rate-double")).forEach { (v, label, tag) ->
                    val click = { onVote(current.id, v); index = position + 1 }
                    if (v == 0) {
                        OutlinedButton(onClick = click, modifier = Modifier.weight(1.3f).testTag(tag)) { Text(label, maxLines = 1) }
                    } else {
                        FilledTonalButton(onClick = click, modifier = Modifier.weight(1f).testTag(tag)) { Text(label, maxLines = 1) }
                    }
                }
            }
            TextButton(onClick = { index = position + 1 }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("rate-skip")) { Text("Überspringen") }
        }
    }
}

/** Photos, name, region and category, ratings, distances, description, the assistant's reason and the others' votes. */
@Composable
private fun RatingCard(snapshot: TripSnapshot, saved: SavedPlace, me: String, onOpenPlace: (String) -> Unit, onOpenMap: (String) -> Unit, modifier: Modifier) {
    val place = snapshot.places[saved.placeId]
    val region = snapshot.regions.firstOrNull { it.id == saved.regionId }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("rating-card-${saved.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val images = place?.imageKeys.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        if (images.isNotEmpty()) {
            // Keyed by place: a new place starts at its first photo.
            val pager = androidx.compose.runtime.key(saved.id) { rememberPagerState { images.size } }
            Box {
                HorizontalPager(pager, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { i ->
                    AsyncImage(wanderlogUrl(images[i], large = true), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (images.size > 1) {
                    Text(
                        "${pager.currentPage + 1}/${images.size}", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        } else {
            PlacePhotosRow(place)
        }
        Text(place?.name?.takeIf { it.isNotBlank() } ?: "Ort", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(region?.name, categoryLabel(saved.category)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = muted)
        RatingStrip(place, saved)
        stayDistance(snapshot, saved)?.let { d ->
            Text("🏨 ${distanceLabel(d.km)} zur Unterkunft (${d.regionName})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rating-stay"))
        }
        centreDistance(snapshot, saved)?.let { d ->
            Text("${distanceLabel(d.km)} vom Zentrum ${d.regionName}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rating-centre"))
        }
        place?.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        saved.worthReason.takeIf { it.isNotBlank() }?.let { Text("KI: $it", style = MaterialTheme.typography.bodySmall, color = muted) }
        // The place and the spots from the research; a tap opens the full map (hidden without coordinates).
        val points = androidx.compose.runtime.remember(place, saved.dossier) { placeMapPoints(place, saved.dossier) }
        val accent = region?.let { de.wanderlogplus.app.ui.regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
        PlaceMapCard(points, accent, { onOpenMap(saved.id) })
        val others = snapshot.votes.filter { it.savedPlaceId == saved.id && it.voter != me && Votes.valid(it) }
        voteNames(others).takeIf { it.isNotBlank() }?.let { Text("Andere: $it", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("rating-others")) }
        TextButton(onClick = { onOpenPlace(saved.id) }, modifier = Modifier.testTag("rating-more")) { Text("Mehr") }
    }
}
