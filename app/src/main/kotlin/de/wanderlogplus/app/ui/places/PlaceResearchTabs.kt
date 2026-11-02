package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.app.ui.reels.worthLabel
import de.wanderlogplus.app.ui.reels.worthTone
import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.model.CriterionScore
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.SavedPlace
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Recherchieren (KI)" for one place: [running] while its job is on the server. */
data class ResearchAction(val running: Boolean, val onResearch: () -> Unit)

/** "Ø 3,8" over the scored criteria; null without scores. */
fun averageScore(criteria: List<CriterionScore>): String? {
    val scored = criteria.filter { it.score in 1..5 }
    if (scored.isEmpty()) return null
    return "Ø " + String.format(Locale.GERMAN, "%.1f", scored.map { it.score }.average())
}

/** Label of a score row: the stored one, else the catalogue's, else the key. */
fun criterionLabel(c: CriterionScore): String = c.label.ifBlank { Criteria.byKey(c.key)?.label ?: c.key }

/** "Recherchiert am 02.11.2026"; null when the dossier holds only a judgement. */
fun researchedLabel(d: PlaceDossier?, zone: ZoneId = ZoneId.systemDefault()): String? =
    d?.researchedAt?.takeIf { it > 0 }?.let {
        "Recherchiert am " + DateTimeFormatter.ofPattern("dd.MM.yyyy").format(Instant.ofEpochMilli(it).atZone(zone))
    }

/** Host of a source link for display ("de.wikipedia.org"); the link itself when it has none. */
fun sourceHost(url: String): String =
    runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

/** Tab "Einschätzung": overall verdict with the worth badge, then every criterion with its 1–5 score and note. */
@Composable
internal fun PlaceAssessmentTab(saved: SavedPlace, accent: Color, action: ResearchAction?) {
    val d = saved.dossier
    val verdict = d?.verdict?.ifBlank { null } ?: saved.worthReason.ifBlank { null }
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("place-assessment"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (saved.worth == null && d == null) {
            EmptyHint("Noch nicht eingeschätzt. Die Recherche bewertet den Ort nach deinen KI-Kriterien.", Modifier.padding(top = 8.dp))
            action?.let { ResearchButton(it, again = false) }
            return@Column
        }
        SectionLabel("Gesamtfazit", color = accent)
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved.worth?.let { Badge(BadgeSpec(worthLabel(it), worthTone(it))) }
                    d?.criteria?.let(::averageScore)?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("score-average"))
                    }
                }
                verdict?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("place-verdict")) }
            }
        }
        val criteria = d?.criteria.orEmpty()
        if (criteria.isNotEmpty()) {
            SectionLabel("Kriterien", color = accent)
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    criteria.forEach { CriterionRow(it, accent) }
                }
            }
        } else {
            Text(
                "Für eine Bewertung nach Kriterien den Ort recherchieren.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.let { ResearchButton(it, again = d != null) }
    }
}

@Composable
private fun CriterionRow(c: CriterionScore, accent: Color) {
    Column(Modifier.fillMaxWidth().testTag("criterion-score-${c.key}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(criterionLabel(c), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            ScoreBar(c.score, accent)
            Text("${c.score}/5", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        }
        if (c.note.isNotBlank()) {
            Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScoreBar(score: Int, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            Box(
                Modifier.size(width = 14.dp, height = 8.dp)
                    .background(if (i < score) accent else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** Tab "Recherche": insider tips, hidden spots, good to know, tips, warnings and the sources. */
@Composable
internal fun PlaceResearchTab(saved: SavedPlace, accent: Color, action: ResearchAction?) {
    val d = saved.dossier
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("place-research"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val sections = listOf(
            "Geheimtipps" to d?.insiderTips.orEmpty(),
            "Versteckte Ecken" to d?.hiddenSpots.orEmpty(),
            "Gut zu wissen" to d?.goodToKnow.orEmpty(),
            "Tipps" to d?.tips.orEmpty(),
            "Hinweise" to d?.hints.orEmpty(),
        ).filter { it.second.isNotEmpty() }
        val sources = d?.sources.orEmpty()
        if (sections.isEmpty() && sources.isEmpty()) {
            EmptyHint(
                "Noch nicht recherchiert. Die KI sucht im Netz nach Geheimtipps, versteckten Ecken und allem, was man vorher wissen sollte.",
                Modifier.padding(top = 8.dp),
            )
            action?.let { ResearchButton(it, again = false) }
            return@Column
        }
        researchedLabel(d)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        sections.forEach { (title, lines) ->
            SectionLabel(title, color = accent)
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { line ->
                        Row {
                            Text("•", color = accent, modifier = Modifier.padding(end = 8.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (sources.isNotEmpty()) {
            val uri = LocalUriHandler.current
            SectionLabel("Quellen", color = accent)
            SurfaceCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    sources.forEach { url ->
                        Text(
                            sourceHost(url),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(url) } }.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        action?.let { ResearchButton(it, again = true) }
    }
}

@Composable
private fun ResearchButton(action: ResearchAction, again: Boolean) {
    val label = when {
        action.running -> "Wird recherchiert …"
        again -> "Neu recherchieren (KI)"
        else -> "Recherchieren (KI)"
    }
    val content: @Composable () -> Unit = {
        if (action.running) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        }
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
    val mod = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("place-research-button")
    if (again) {
        OutlinedButton(onClick = action.onResearch, enabled = !action.running, modifier = mod) { content() }
    } else {
        Button(onClick = action.onResearch, enabled = !action.running, modifier = mod) { content() }
    }
}
