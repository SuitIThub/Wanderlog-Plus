package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.categorySymbol
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.bleedHorizontally
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.app.ui.design.PhotoCardStyle
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.TagRank
import de.wanderlogplus.shared.query.isOpenOn
import de.wanderlogplus.shared.query.tagRank

/** Vorschläge tab: category and weekday filter chips, then compact cards with ＋ (tag `suggestion-add-<id>`). */
@Composable
internal fun DaySuggestionsTab(
    hasRegion: Boolean,
    date: String,
    shown: List<Suggestion>,
    accent: Color,
    category: PlaceCategory?,
    onCategory: (PlaceCategory?) -> Unit,
    openOnly: Boolean,
    onOpenOnly: (Boolean) -> Unit,
    onAdd: (date: String, savedPlaceId: String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasRegion) {
            EmptyHint("Reisetag – keine Vorschläge")
            return@Column
        }
        // The chip row scrolls edge to edge (like in the region list) instead of being cut at the page padding.
        Row(
            Modifier.bleedHorizontally(16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf<PlaceCategory?>(null) + PlaceCategory.entries).forEach { c ->
                FilterChip(
                    selected = category == c,
                    onClick = { onCategory(c) },
                    label = { Text(categoryLabel(c)) },
                    modifier = Modifier.testTag("filter-category-${c?.name ?: "ALL"}"),
                )
            }
        }
        FilterChip(
            selected = openOnly,
            onClick = { onOpenOnly(!openOnly) },
            label = { Text("an diesem Wochentag geöffnet") },
            modifier = Modifier.testTag("filter-weekday"),
        )
        val dow = dayOfWeekSunday0(date)
        Column(Modifier.fillMaxWidth().testTag("suggestion-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) EmptyHint("Keine Vorschläge")
            shown.forEach { s -> SuggestionCard(s, dow, accent) { onAdd(date, s.saved.id) } }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, dow: Int?, accent: Color, onAdd: () -> Unit) {
    val open = dow != null && s.place?.let { isOpenOn(it.openingPeriods, dow) } == true
    val tagBadges = s.saved.tags.map { t ->
        BadgeSpec(t, when (tagRank(listOf(t))) { TagRank.STAR -> BadgeTone.Accent; TagRank.WARNING -> BadgeTone.Warning; else -> BadgeTone.Neutral })
    }
    PhotoCard(
        title = s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort",
        subtitle = listOfNotNull(categoryLabel(s.saved.category), s.place?.rating?.let { ratingLabel(it) }).joinToString(" · "),
        imageUrl = s.place?.heroImageUrl(),
        placeholderSymbol = categorySymbol(s.saved.category),
        accent = accent,
        style = PhotoCardStyle.Compact,
        badges = (if (open) listOf(BadgeSpec("geöffnet", BadgeTone.Positive)) else emptyList()) + tagBadges,
        trailing = {
            FilledTonalIconButton(onClick = onAdd, modifier = Modifier.testTag("suggestion-add-${s.saved.id}")) {
                Icon(Icons.Filled.Add, contentDescription = "Zum Tag hinzufügen")
            }
        },
        modifier = Modifier.testTag("suggestion-${s.saved.id}"),
    )
}
