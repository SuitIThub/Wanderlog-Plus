package de.wanderlogplus.app.ui.guides

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.guide.CorrectionState
import de.wanderlogplus.shared.guide.GuideStatements
import de.wanderlogplus.shared.model.StatementKind
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.GuideCorrection
import de.wanderlogplus.shared.sync.TipVerdict

const val GUIDE_CORRECTIONS_ROUTE = "guide-corrections?guide={guideId}"

/** The corrections of all guides, or of one guide. */
fun guideCorrectionsRoute(guideId: String? = null) = "guide-corrections?guide=" + java.net.URLEncoder.encode(guideId.orEmpty(), "UTF-8")

object GuideCorrections {
    /** Whether [job] is a fact check (not a write or tidy that also checks). */
    fun isCheck(job: AiJobInfo) = job.source?.startsWith("check") == true

    /** The corrections of the ended [jobs] (for [guideId], or all), newest job first, each change once. */
    fun of(jobs: List<AiJobInfo>, guideId: String? = null): List<GuideCorrection> =
        jobs.filter { !GuideJobs.isActive(it) }.sortedByDescending { it.createdAt }
            .flatMap { it.corrections }
            .filter { guideId == null || it.guideId == guideId }
            .distinctBy { Triple(it.guideId, it.before, it.after) }

    /** "2 Korrekturen", "1 Hinweis" or "alles stimmt". */
    fun summary(cs: List<GuideCorrection>): String {
        val notes = cs.count { it.note.isNotBlank() && it.after == it.before }
        val fixed = cs.size - notes
        val parts = listOfNotNull(
            fixed.takeIf { it > 0 }?.let { if (it == 1) "1 Korrektur" else "$it Korrekturen" },
            notes.takeIf { it > 0 }?.let { if (it == 1) "1 Hinweis" else "$it Hinweise" },
        )
        return if (parts.isEmpty()) "alles stimmt" else parts.joinToString(", ")
    }

    fun badge(c: GuideCorrection): BadgeSpec = when {
        c.after.isBlank() -> BadgeSpec("gestrichen", BadgeTone.Warning)
        c.note.isNotBlank() && c.after == c.before -> BadgeSpec("unsicher ⚠", BadgeTone.Neutral)
        c.note.isNotBlank() -> BadgeSpec("vorsichtiger – unsicher ⚠", BadgeTone.Neutral)
        c.verdict == TipVerdict.FALSE -> BadgeSpec("falsch – ersetzt", BadgeTone.Warning)
        else -> BadgeSpec("korrigiert", BadgeTone.Accent)
    }

    fun where(c: GuideCorrection): String = when (c.kind) {
        StatementKind.SUMMARY -> "Auf einen Blick"
        StatementKind.TEXT -> c.heading
        StatementKind.DO -> "${c.heading} · Do"
        StatementKind.DONT -> "${c.heading} · Don't"
        StatementKind.DETAILS -> "${c.heading} · Mehr dazu"
    }
}

/** A small ⚠ after an uncertain statement; a tap shows why. Tag `guide-note`. */
@Composable
fun NoteMark(note: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Icon(
        Icons.Filled.WarningAmber,
        contentDescription = "Hinweis",
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = modifier.size(18.dp).clickable { open = true }.testTag("guide-note"),
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null) },
            title = { Text("Nicht eindeutig") },
            text = { Text(note) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } },
        )
    }
}

/** A statement with its ⚠ when the guide has a hint on it. */
@Composable
fun NotedText(text: String, note: String?, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    if (note == null) {
        content(modifier)
        return
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        content(Modifier.weight(1f, fill = false))
        NoteMark(note, Modifier.padding(top = 2.dp))
    }
}

/**
 * What the fact checks changed ([guideId], or every guide): per change the new wording, badge, the old one, why and the
 * sources, and "Zurücknehmen" while the change is still in the guide. Tags `guide-correction-<i>`, `guide-revert-<i>`.
 */
@Composable
fun GuideCorrectionsScreen(
    snapshot: TripSnapshot,
    actions: GuideActions?,
    jobs: List<AiJobInfo>,
    guideId: String?,
    onOpenGuide: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = GuideCorrections.of(jobs, guideId)
    val guides = snapshot.guides.associateBy { it.id }
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxSize().testTag("guide-corrections")) {
        Row {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Korrekturen", style = de.wanderlogplus.app.ui.HeroTitle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Die Prüfung hat diese Stellen schon geändert. Hier siehst du, was und warum – und kannst einzelne Änderungen zurücknehmen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (all.isEmpty()) EmptyHint("Keine Korrekturen: bei der letzten Prüfung hat alles gestimmt.")
            var i = 0
            all.groupBy { it.guideId }.forEach { (gid, cs) ->
                val g = guides[gid]
                TextButton(onClick = { if (g != null) onOpenGuide(gid) }, enabled = g != null) {
                    Text(g?.title ?: cs.first().guideTitle.ifBlank { "Gelöschter Guide" }, fontWeight = FontWeight.SemiBold)
                }
                cs.forEach { c ->
                    val index = i++
                    var showSources by remember(c) { mutableStateOf(false) }
                    val state = g?.let { GuideStatements.stateOf(it, c) } ?: CorrectionState.CHANGED
                    SurfaceCard(Modifier.testTag("guide-correction-$index")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(GuideCorrections.where(c), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (c.after.isNotBlank()) Text(de.wanderlogplus.app.ui.euroed(c.after), style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Badge(GuideCorrections.badge(c))
                                when (state) {
                                    CorrectionState.REVERTED -> Badge(BadgeSpec("zurückgenommen", BadgeTone.Neutral))
                                    CorrectionState.CHANGED -> Badge(BadgeSpec("inzwischen geändert", BadgeTone.Neutral))
                                    CorrectionState.APPLIED -> {}
                                }
                            }
                            if (c.after != c.before) {
                                Text(
                                    "Vorher: " + c.before,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = if (c.after.isBlank()) TextDecoration.LineThrough else null,
                                )
                            }
                            if (c.note.isNotBlank()) Text("⚠ " + c.note, style = MaterialTheme.typography.bodySmall)
                            if (c.reason.isNotBlank() && c.reason != c.note) Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                if (c.sources.isNotEmpty()) TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "Quellen ausblenden" else "Quellen (${c.sources.size})") }
                                Box(Modifier.weight(1f))
                                if (actions != null && state == CorrectionState.APPLIED) {
                                    TextButton(onClick = { actions.revert(c) }, modifier = Modifier.testTag("guide-revert-$index")) {
                                        Text(if (c.after == c.before) "Hinweis entfernen" else "Zurücknehmen")
                                    }
                                }
                            }
                            if (showSources) c.sources.forEach { s ->
                                Text(
                                    runCatching { java.net.URI(s).host?.removePrefix("www.") }.getOrNull() ?: s,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(s) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
