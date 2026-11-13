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
import de.wanderlogplus.shared.sync.ReelTip
import de.wanderlogplus.shared.sync.TipVerdict
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
                "Teile ein Instagram-Reel oder einen Post (auch Bilder und Galerien) an die App, oder füge hier den Link ein. Die KI sucht die Orte darin, prüft, ob sie auf eurer Route liegen, und recherchiert sie. Allgemeine Tipps prüft sie auf Wahrheit und schlägt sie für eure Guides vor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(link, { link = it }, label = { Text("Reel- oder Post-Link") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reel-link"))
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
                            j.status == "DONE" && j.tips.isNotEmpty() -> "${j.candidates.size} Orte, ${j.tips.size} Tipps gefunden · neu"
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
    /** The picked places, the picked tips (with their chosen guide or place) and the saved places whose reel facts go into their research. */
    onImport: (List<ReelPick>, List<ReelTip>, List<de.wanderlogplus.shared.sync.ReelCandidate>) -> Unit,
    onOpenPlaces: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the detailed "Ablauf" of this analysis; null hides the button. */
    onShowProgress: (() -> Unit)? = null,
    /** Opens the place search to set candidate [index]'s position by hand; null hides "Position suchen". */
    onFindPosition: ((index: Int) -> Unit)? = null,
) {
    // "Trotzdem importieren": the candidate counts as new (no duplicate) and is researched after the import.
    val forced = remember(job?.id) { mutableStateMapOf<Int, Boolean>() }
    val candidates = job?.candidates.orEmpty().mapIndexed { i, c -> if (forced[i] == true) c.copy(savedPlaceId = null) else c }
    val picked = remember(job?.id, candidates.size) {
        mutableStateMapOf<Int, Boolean>().apply { candidates.forEachIndexed { i, c -> put(i, c.main && c.onRoute && importable(c)) } }
    }
    val regions = remember(job?.id, candidates.size, snapshot) {
        mutableStateMapOf<Int, String?>().apply { if (snapshot != null) candidates.forEachIndexed { i, c -> put(i, defaultRegion(snapshot, c)) } }
    }
    // Tips: picked ones and the guide each goes into (changeable).
    val tips = remember(job?.id, job?.tips) { mutableStateMapOf<Int, ReelTip>().apply { job?.tips.orEmpty().forEachIndexed { i, t -> put(i, t) } } }
    val tipPicked = remember(job?.id, job?.tips?.size) {
        mutableStateMapOf<Int, Boolean>().apply { job?.tips.orEmpty().forEachIndexed { i, t -> put(i, tipPreselected(t)) } }
    }
    // Saved places with facts from the reel: "Zum Ort hinzufügen", picked from the start.
    val factsPicked = remember(job?.id, candidates.size) {
        mutableStateMapOf<Int, Boolean>().apply { candidates.forEachIndexed { i, c -> if (c.savedPlaceId != null && c.reelFacts.isNotEmpty()) put(i, true) } }
    }
    // Places a tip may go to: the reel's saved places and the ones the check suggested.
    val placeOptions = buildMap<String, String> {
        candidates.forEach { c -> c.savedPlaceId?.let { put(it, c.name) } }
        val names = snapshot?.let { s -> s.savedPlaces.associate { it.id to (s.places[it.placeId]?.name ?: "Ort") } }.orEmpty()
        job?.tips.orEmpty().flatMap { it.placeIds }.forEach { id -> if (id !in this) put(id, names[id] ?: "Ort") }
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
            candidates.isEmpty() && tips.isEmpty() -> EmptyHint("Im Beitrag wurden weder Orte noch allgemeine Tipps erkannt.", Modifier.padding(16.dp))
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
                                if (c.savedPlaceId != null && !imported && c.lat != null && c.lng != null) {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = { forced[i] = true; picked[i] = true; if (snapshot != null) regions[i] = defaultRegion(snapshot, c) },
                                        modifier = Modifier.testTag("reel-import-anyway-$i"),
                                    ) { Text("Trotzdem importieren") }
                                }
                                if (forced[i] == true && !imported) {
                                    Text("Wird als eigener Ort übernommen und danach recherchiert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (c.savedPlaceId != null && c.reelFacts.isNotEmpty() && forced[i] != true) {
                                    Text("Neu aus dem Reel", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    c.reelFacts.forEach { Text("• " + de.wanderlogplus.app.ui.euroed(it), style = MaterialTheme.typography.bodyMedium) }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = factsPicked[i] == true,
                                            onCheckedChange = { factsPicked[i] = it },
                                            enabled = !imported,
                                            modifier = Modifier.testTag("reel-facts-$i"),
                                        )
                                        Text("Zum Ort hinzufügen", style = MaterialTheme.typography.bodyMedium)
                                    }
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
                    if (tips.isNotEmpty()) {
                        item { SectionLabel("Tipps") }
                        items(tips.size) { i ->
                            val t = tips.getValue(i)
                            ReelTipCard(
                                tip = t,
                                index = i,
                                checked = tipPicked[i] == true,
                                enabled = !imported && tipSelectable(t),
                                snapshot = snapshot,
                                places = placeOptions,
                                onCheck = { tipPicked[i] = it },
                                onTarget = { tips[i] = it },
                            )
                        }
                    }
                }
                val count = picked.count { it.value }
                val tipCount = tipPicked.count { it.value && tips[it.key]?.let(::tipSelectable) == true } +
                    factsPicked.count { it.value && forced[it.key] != true }
                if (imported) {
                    if (candidates.isNotEmpty()) {
                        Button(onClick = onOpenPlaces, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("reel-imported")) { Text("Übernommen – zu den Orten") }
                    } else {
                        Text("Übernommen – Guides und Orte werden ergänzt.", Modifier.padding(16.dp).testTag("reel-imported"), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Button(
                        onClick = {
                            onImport(
                                candidates.indices.filter { picked[it] == true }.map { ReelPick(candidates[it], regions[it], research = forced[it] == true) },
                                tips.keys.sorted().filter { tipPicked[it] == true }.map { tips.getValue(it) }.filter(::tipSelectable),
                                candidates.indices.filter { factsPicked[it] == true && forced[it] != true }.map { candidates[it] },
                            )
                        },
                        enabled = count + tipCount > 0,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("reel-import"),
                    ) { Text(importLabel(count, tipCount)) }
                }
            }
        }
    }
}

/** "2 Orte und 1 Tipp übernehmen", "1 Ort übernehmen", "3 Tipps übernehmen". */
fun importLabel(places: Int, tips: Int): String {
    val p = if (places == 1) "1 Ort" else "$places Orte"
    val t = if (tips == 1) "1 Tipp" else "$tips Tipps"
    return when {
        tips == 0 -> "$p übernehmen"
        places == 0 -> "$t übernehmen"
        else -> "$p und $t übernehmen"
    }
}

/** Badge text and tone of a checked tip. */
private fun tipBadge(t: ReelTip, guideTitle: (String) -> String?): BadgeSpec = when {
    t.coveredBy != null -> BadgeSpec("steht schon in „" + (guideTitle(t.coveredBy!!) ?: "einem Guide") + "“", BadgeTone.Neutral)
    t.verdict == TipVerdict.TRUE -> BadgeSpec("stimmt", BadgeTone.Positive)
    t.verdict == TipVerdict.PARTLY -> BadgeSpec("übertrieben – korrigiert", BadgeTone.Accent)
    t.verdict == TipVerdict.FALSE -> BadgeSpec("falsch", BadgeTone.Warning)
    else -> BadgeSpec("nicht belegt", BadgeTone.Neutral)
}

/**
 * One tip of a reel: checked text, verdict, reason, sources and the guide it goes into.
 * Tags `reel-tip-<i>` (checkbox) and `reel-tip-target-<i>`.
 */
@Composable
private fun ReelTipCard(
    tip: ReelTip,
    index: Int,
    checked: Boolean,
    enabled: Boolean,
    snapshot: TripSnapshot?,
    /** Saved places the tip may go to instead of a guide: id to name. */
    places: Map<String, String>,
    onCheck: (Boolean) -> Unit,
    onTarget: (ReelTip) -> Unit,
) {
    val guides = snapshot?.guides.orEmpty()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var showSources by remember { mutableStateOf(false) }
    SurfaceCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked && tipSelectable(tip), onCheckedChange = onCheck, enabled = enabled, modifier = Modifier.testTag("reel-tip-$index"))
                Text(de.wanderlogplus.app.ui.euroed(tip.text.ifBlank { tip.claim }), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
            Badge(tipBadge(tip) { id -> guides.firstOrNull { it.id == id }?.title })
            if (tip.verdict == TipVerdict.PARTLY || tip.verdict == TipVerdict.FALSE) {
                Text("Im Beitrag: " + tip.claim, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tip.coveredQuote.isNotBlank()) Text("„" + tip.coveredQuote + "“", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tip.reason.isNotBlank()) Text(tip.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tip.sources.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "Quellen ausblenden" else "Quellen (${tip.sources.size})") }
                if (showSources) tip.sources.forEach { s ->
                    Text(
                        runCatching { java.net.URI(s).host?.removePrefix("www.") }.getOrNull() ?: s,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { runCatching { uri.openUri(s) } },
                    )
                }
            }
            if (enabled) {
                val newTitle = tip.targetTitle ?: tip.topic.ifBlank { "Reisetipps" }
                val catalog = tip.targetTopic?.let { de.wanderlogplus.shared.guide.GuideTopics.byKey(it) }
                val options = buildList<Pair<String, String>> {
                    places.forEach { (id, name) -> add("p:$id" to "In Recherche: $name") }
                    guides.sortedBy { it.title.lowercase() }.forEach { add("g:" + it.id to it.title) }
                    if (catalog != null) add("t:" + catalog.key to "Neuer Guide: " + catalog.name)
                    add("n:$newTitle" to "Neuer Guide: $newTitle")
                }
                val current = when {
                    tip.placeIds.isNotEmpty() -> "p:" + tip.placeIds.first()
                    tip.guideId != null -> "g:" + tip.guideId
                    tip.targetTopic != null -> "t:" + tip.targetTopic
                    else -> "n:$newTitle"
                }
                LabeledDropdown(
                    tag = "reel-tip-target-$index",
                    label = "Ziel",
                    value = options.firstOrNull { it.first == current }?.second ?: "Neuer Guide: $newTitle",
                    options = options,
                    onSelect = { key ->
                        val v = key.substring(2)
                        onTarget(
                            when (key[0]) {
                                'p' -> tip.copy(placeIds = listOf(v), guideId = null, targetTopic = null, targetTitle = null)
                                'g' -> tip.copy(placeIds = emptyList(), guideId = v, targetTopic = null, targetTitle = null)
                                't' -> tip.copy(placeIds = emptyList(), guideId = null, targetTopic = v, targetTitle = null)
                                else -> tip.copy(placeIds = emptyList(), guideId = null, targetTopic = null, targetTitle = v)
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
