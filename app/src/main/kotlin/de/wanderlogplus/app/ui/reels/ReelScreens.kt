package de.wanderlogplus.app.ui.reels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.Worth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun worthTone(w: Worth) = when (w) {
    Worth.MUST -> BadgeTone.Accent
    Worth.WORTH -> BadgeTone.Positive
    Worth.OPTIONAL -> BadgeTone.Neutral
    Worth.SKIP -> BadgeTone.Warning
}

/** Mehr → "Reels (KI)": paste a link to start, and earlier analyses. Tags `reel-link`, `reel-start`, `reel-job-<id>`. */
@Composable
fun ReelsScreen(
    jobs: List<AiJobInfo>,
    isImported: (String) -> Boolean,
    onStart: (url: String) -> Unit,
    onOpen: (jobId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var link by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxSize().imePadding().testTag("reels-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text("Reels auswerten", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Teile ein Instagram-Reel an die App, oder füge hier den Link ein. Die KI sucht die Orte darin, prüft, ob sie auf eurer Route liegen, und recherchiert sie.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(link, { link = it }, label = { Text("Reel-Link") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reel-link"))
            Button(onClick = { onStart(link.trim()) }, enabled = link.isNotBlank(), modifier = Modifier.align(Alignment.End).testTag("reel-start")) { Text("Auswerten") }
            SectionLabel("Bisherige Auswertungen")
        }
        if (jobs.isEmpty()) {
            EmptyHint("Noch keine Reels ausgewertet", Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(jobs, key = { it.id }) { j ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(j.id) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("reel-job-${j.id}")) {
                        Text(j.summary ?: (j.source ?: "Reel"), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val state = when {
                            isImported(j.id) -> "übernommen"
                            j.status == "DONE" -> "${j.candidates.size} Orte gefunden · neu"
                            else -> statusLabel(j.status)
                        }
                        Text(
                            SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date(j.createdAt)) + " · " + state,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One analysis: progress while it runs, the reason when it failed, and when done the found places with checkboxes
 * (preselected: on the route and with coordinates), region per place, judgement, description and tips.
 * Tags: `reel-status`, `reel-error`, `reel-pick-<i>`, `reel-region-<i>`, `reel-import`, `reel-imported`.
 */
@Composable
fun ReelJobScreen(
    job: AiJobInfo?,
    snapshot: TripSnapshot?,
    imported: Boolean,
    onImport: (List<ReelPick>) -> Unit,
    onOpenPlaces: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the detailed "Ablauf" of this analysis; null hides the button. */
    onShowProgress: (() -> Unit)? = null,
    /** Opens the place search to set candidate [index]'s position by hand; null hides "Position suchen". */
    onFindPosition: ((index: Int) -> Unit)? = null,
) {
    val candidates = job?.candidates.orEmpty()
    val picked = remember(job?.id, candidates.size) {
        mutableStateMapOf<Int, Boolean>().apply { candidates.forEachIndexed { i, c -> put(i, c.main && c.onRoute && importable(c)) } }
    }
    val regions = remember(job?.id, candidates.size, snapshot) {
        mutableStateMapOf<Int, String?>().apply { if (snapshot != null) candidates.forEachIndexed { i, c -> put(i, defaultRegion(snapshot, c)) } }
    }
    val placedByHand = candidates.mapIndexedNotNull { i, c -> i.takeIf { c.positionSetByHand } }
    LaunchedEffect(placedByHand) {
        placedByHand.forEach { i ->
            if (regions[i] == null && snapshot != null) regions[i] = defaultRegion(snapshot, candidates[i])
            if (picked[i] != true) picked[i] = true
        }
    }
        Column(modifier.fillMaxSize().testTag("reel-job")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text("Reel-Auswertung", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            onShowProgress?.let { show ->
                androidx.compose.material3.TextButton(onClick = show, modifier = Modifier.testTag("reel-progress")) { Text("Ablauf") }
            }
        }
        when {
            job == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            job.status == "FAILED" -> Text(errorText(job.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).testTag("reel-error"))
            !isFinished(job) -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(statusLabel(job.status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("reel-status"))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                job.steps.lastOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reel-last-step")) }
                Text("Das dauert meist ein bis drei Minuten. Du kannst die Seite verlassen; die Auswertung läuft auf dem Server weiter.", style = MaterialTheme.typography.bodySmall)
                onShowProgress?.let { show ->
                    androidx.compose.material3.OutlinedButton(onClick = show, modifier = Modifier.fillMaxWidth().testTag("reel-progress-live")) { Text("Ablauf live ansehen") }
                }
            }
            candidates.isEmpty() -> EmptyHint("Im Reel wurde kein konkreter Ort erkannt.", Modifier.padding(16.dp))
            else -> {
                job.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
                // Places only named for orientation (stations, landmarks in the background) are folded away.
                var showReferences by remember(job.id) { mutableStateOf(false) }
                val references = candidates.indices.filter { !candidates[it].main }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(candidates) { i, c ->
                        if (!c.main && i == references.first()) {
                            androidx.compose.material3.TextButton(onClick = { showReferences = !showReferences }, modifier = Modifier.testTag("reel-references")) {
                                Text((if (showReferences) "Nur erwähnt ausblenden" else "Nur erwähnt (${references.size})") + " – Bahnhöfe, Viertel, Kulisse")
                            }
                        }
                        if (c.main || showReferences) SurfaceCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = picked[i] == true,
                                        onCheckedChange = { picked[i] = it },
                                        enabled = !imported && importable(c),
                                        modifier = Modifier.testTag("reel-pick-$i"),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(listOfNotNull(c.city, routeLabel(c)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (c.savedPlaceId != null) Badge(BadgeSpec("bereits gespeichert", BadgeTone.Neutral))
                                    else if (!c.onRoute) Badge(BadgeSpec("abseits", BadgeTone.Warning))
                                    c.worth?.let { Badge(BadgeSpec(worthLabel(it), worthTone(it))) }
                                }
                                if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodyMedium)
                                if (c.worthReason.isNotBlank()) Text(c.worthReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val note = reelNote(c, null)
                                if (note.isNotBlank()) MarkdownText(note)
                                if (snapshot != null && importable(c) && !imported) {
                                    val options = listOf<Pair<String?, String>>(null to "Ohne Region") + snapshot.regions.map { it.id to it.name }
                                    LabeledDropdown(
                                        tag = "reel-region-$i",
                                        label = "Region",
                                        value = options.firstOrNull { it.first == regions[i] }?.second ?: "Ohne Region",
                                        options = options,
                                        onSelect = { regions[i] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (!importable(c) && c.savedPlaceId == null) {
                                    Text(missingPositionText(c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("reel-no-position-$i"))
                                    if (onFindPosition != null && !imported) {
                                        androidx.compose.material3.OutlinedButton(onClick = { onFindPosition(i) }, modifier = Modifier.testTag("reel-find-position-$i")) {
                                            Text("Position suchen")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val count = picked.count { it.value }
                if (imported) {
                    Button(onClick = onOpenPlaces, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("reel-imported")) { Text("Übernommen – zu den Orten") }
                } else {
                    Button(
                        onClick = { onImport(candidates.indices.filter { picked[it] == true }.map { ReelPick(candidates[it], regions[it]) }) },
                        enabled = count > 0,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("reel-import"),
                    ) { Text(if (count == 1) "1 Ort übernehmen" else "$count Orte übernehmen") }
                }
            }
        }
    }
}
