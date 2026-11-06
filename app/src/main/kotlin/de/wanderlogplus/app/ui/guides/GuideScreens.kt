package de.wanderlogplus.app.ui.guides

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.guide.GuideMatch
import de.wanderlogplus.shared.guide.GuideSuggestions
import de.wanderlogplus.shared.guide.GuideTopics
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuidePhrase
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.sync.AiJobInfo

const val GUIDES_ROUTE = "guides"
const val GUIDE_ROUTE = "guide/{guideId}"

fun guideRoute(id: String) = "guide/" + java.net.URLEncoder.encode(id, "UTF-8")

/** All guides grouped by their main topic, with the trip's suggestions and the running guides job on top. */
@Composable
fun GuidesScreen(
    snapshot: TripSnapshot,
    actions: GuideActions?,
    jobs: List<AiJobInfo>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val world = remember(snapshot) { snapshot.guideWorld() }
    val suggestions = remember(snapshot) { GuideSuggestions.open(world, snapshot.tripDates(), snapshot.guidePrefs) }
    val chosen = remember(suggestions) { mutableStateListOf<String>().apply { addAll(suggestions) } }
    Column(modifier.fillMaxSize().testTag("guides")) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Guides", style = de.wanderlogplus.app.ui.HeroTitle, color = MaterialTheme.colorScheme.onSurface)
            jobs.filter(GuideJobs::isActive).forEach { running ->
                SurfaceCard(Modifier.testTag("guides-running")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val what = if (running.guideIds.isNotEmpty()) "Wird überarbeitet" else "Wird erstellt"
                        Text("$what: ${GuideJobs.names(running)}", fontWeight = FontWeight.SemiBold)
                        if (running.total > 1) Text("${running.progress} von ${running.total}", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { if (running.total == 0) 0f else running.progress / running.total.toFloat() }, Modifier.fillMaxWidth())
                        running.steps.lastOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            GuideJobs.recentFailures(jobs).forEach { (job, why) ->
                SurfaceCard(Modifier.testTag("guides-failed")) {
                    Text(
                        "Guide-Auftrag „${GuideJobs.names(job)}“ fehlgeschlagen: $why",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (suggestions.isNotEmpty() && actions != null) {
                SurfaceCard(Modifier.testTag("guide-suggestions")) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("Vorgeschlagen für eure Reise", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        suggestions.forEach { key ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = key in chosen, onCheckedChange = { on -> if (on) chosen += key else chosen -= key })
                                Text(GuideTopics.byKey(key)?.name ?: key, Modifier.weight(1f))
                                IconButton(onClick = { actions.dismiss(key) }) { Icon(Icons.Filled.Close, contentDescription = "Nicht vorschlagen") }
                            }
                        }
                        Button(
                            onClick = { actions.createSelected(chosen.toList()) },
                            enabled = chosen.isNotEmpty(),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("guides-create"),
                        ) { Text("Ausgewählte erstellen (${chosen.size})") }
                    }
                }
            }
            val guides = snapshot.guides
            if (guides.isEmpty()) {
                EmptyHint("Noch keine Guides. Hake oben Themen ab oder bitte den Chat um einen Guide.")
            } else {
                val byId = guides.associateBy { it.id }
                fun root(g: Guide): Guide = generateSequence(g) { it.parentId?.let(byId::get) }.last()
                guides.groupBy { root(it) }.entries
                    .sortedWith(compareBy({ GuideTopics.orderOf(it.key.topic) }, { it.key.title.lowercase() }))
                    .forEach { (top, members) ->
                        SurfaceCard {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                (listOf(top) + (members - top).sortedBy { it.title.lowercase() }).forEach { g ->
                                    GuideRow(g, sub = g != top) { onOpen(g.id) }
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun GuideRow(g: Guide, sub: Boolean, onClick: () -> Unit) {
    SurfaceCard(onClick = onClick, modifier = Modifier.padding(start = if (sub) 24.dp else 0.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(g.title.ifBlank { "Guide" }, fontWeight = FontWeight.SemiBold)
            if (g.summary.isNotBlank()) Text(g.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

/** One guide: summary, sections with do/don't lists, sub-topics, phrases to show or hear, sources. */
@Composable
fun GuideScreen(
    snapshot: TripSnapshot,
    guideId: String,
    actions: GuideActions?,
    onBack: () -> Unit,
    onOpenGuide: (String) -> Unit,
    onImprove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    jobs: List<AiJobInfo> = emptyList(),
) {
    val g = snapshot.guides.firstOrNull { it.id == guideId }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<GuidePhrase?>(null) }
    val speaker = rememberPhraseSpeaker()
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxSize().testTag("guide-detail")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Box(Modifier.weight(1f))
            if (g != null && (actions != null || onImprove != null)) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mehr") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (actions != null) DropdownMenuItem(text = { Text("Bearbeiten") }, onClick = { menu = false; editing = true })
                        if (onImprove != null) DropdownMenuItem(text = { Text("Im Chat verbessern") }, onClick = { menu = false; onImprove() })
                        if (actions != null) DropdownMenuItem(text = { Text("Löschen") }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
        }
        if (g == null) {
            Text("Guide nicht gefunden", Modifier.padding(16.dp))
            return@Column
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(g.title, style = de.wanderlogplus.app.ui.HeroTitle, color = MaterialTheme.colorScheme.onSurface)
            GuideJobs.stateOf(g, jobs)?.let { GuideJobBanner(it) }
            if (g.summary.isNotBlank()) SurfaceCard { Text(g.summary, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
            g.sections.forEach { s ->
                SurfaceCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.heading, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        if (s.text.isNotBlank()) Text(s.text)
                        s.dos.forEach { DoLine(it, good = true) }
                        s.donts.forEach { DoLine(it, good = false) }
                    }
                }
            }
            val children = snapshot.guides.filter { it.parentId == g.id }.sortedBy { it.title.lowercase() }
            if (children.isNotEmpty()) {
                Text("Unterthemen", fontWeight = FontWeight.SemiBold)
                GuideChips(children, onOpen = onOpenGuide)
            }
            if (g.phrases.isNotEmpty()) {
                Text("Sätze zum Zeigen", fontWeight = FontWeight.SemiBold)
                if (speaker.checked && !speaker.ready) {
                    Text("Vorlesen braucht eine japanische Stimme (Android-Einstellungen → Sprachausgabe).", style = MaterialTheme.typography.bodySmall)
                }
                g.phrases.forEach { p ->
                    SurfaceCard(Modifier.testTag("guide-phrase")) {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.german, style = MaterialTheme.typography.bodySmall)
                                Text(p.japanese, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                                Text(p.romaji, style = MaterialTheme.typography.bodyMedium)
                                if (p.situation.isNotBlank()) Text(p.situation, style = MaterialTheme.typography.labelSmall)
                            }
                            if (speaker.ready) IconButton(onClick = { speaker.speak(p.japanese) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Vorlesen") }
                            IconButton(onClick = { fullscreen = p }) { Icon(Icons.Filled.Fullscreen, contentDescription = "Groß zeigen") }
                        }
                    }
                }
            }
            if (g.sources.isNotEmpty()) {
                Text("Quellen", fontWeight = FontWeight.SemiBold)
                g.sources.forEach { url ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(hostOf(url)) }
                }
            }
            if (g.writtenAt > 0) {
                Text(
                    "Zuletzt recherchiert: " + GuideJobs.writtenText(g.writtenAt),
                    Modifier.padding(bottom = 16.dp).testTag("guide-written-at"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    fullscreen?.let { p ->
        Dialog(onDismissRequest = { fullscreen = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().clickable { fullscreen = null }, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.japanese, fontSize = 48.sp, lineHeight = 60.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    Text(p.romaji, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(p.german, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        }
    }
    if (editing && g != null && actions != null) {
        var title by remember { mutableStateOf(g.title) }
        var summary by remember { mutableStateOf(g.summary) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Guide bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Titel") }, singleLine = true)
                    OutlinedTextField(summary, { summary = it }, label = { Text("Das Wichtigste") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { actions.save(g.copy(title = title.trim().ifBlank { g.title }, summary = summary.trim())); editing = false }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmDelete && g != null && actions != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Guide löschen?") },
            text = { Text("„${g.title}“ verschwindet auf allen Geräten.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete(g.id); onBack() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun DoLine(text: String, good: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (good) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = if (good) "Tun" else "Lassen",
            tint = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(text, Modifier.weight(1f))
    }
}

private fun hostOf(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

/** Guide chips; long press offers "Hier ausblenden" when [onHide] is set, the plus picks a guide to pin when [onAdd] is set. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun GuideChips(guides: List<Guide>, onOpen: (String) -> Unit, onHide: ((String) -> Unit)? = null, onAdd: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var hideFor by remember { mutableStateOf<Guide?>(null) }
    FlowRow(modifier.testTag("guide-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        guides.forEach { g ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(vertical = 4.dp).combinedClickable(onClick = { onOpen(g.id) }, onLongClick = onHide?.let { { hideFor = g } }),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                    Text(g.title.ifBlank { "Guide" }, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (onAdd != null) AssistChip(onClick = onAdd, label = { Text("Guide") }, leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) }, modifier = Modifier.padding(vertical = 4.dp))
    }
    hideFor?.let { g ->
        AlertDialog(
            onDismissRequest = { hideFor = null },
            title = { Text(g.title) },
            text = { Text("Diesen Guide hier nicht mehr anzeigen?") },
            confirmButton = { TextButton(onClick = { onHide?.invoke(g.id); hideFor = null }) { Text("Hier ausblenden") } },
            dismissButton = { TextButton(onClick = { hideFor = null }) { Text("Abbrechen") } },
        )
    }
}

/**
 * The "Guides" block of a detail screen (place, stay, ride, day): the guides that match [target], hide on long
 * press and pin another one with the plus (both only with [LocalGuideActions]). Nothing when there is nothing to show
 * and nothing to pin.
 */
@Composable
fun GuidesSection(snapshot: TripSnapshot, target: GuideTarget, onOpenGuide: (String) -> Unit, modifier: Modifier = Modifier) {
    val actions = LocalGuideActions.current
    val shown = remember(snapshot, target) { GuideMatch.guidesFor(target, snapshot.guideWorld()) }
    var picking by remember { mutableStateOf(false) }
    val others = snapshot.guides.filter { g -> shown.none { it.id == g.id } }
    if (shown.isEmpty() && (actions == null || others.isEmpty())) return
    Column(modifier.fillMaxWidth().testTag("guides-section"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Guides", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        GuideChips(
            shown, onOpen = onOpenGuide,
            onHide = actions?.let { a -> { id: String -> a.hide(id, target) } },
            onAdd = if (actions != null && others.isNotEmpty()) ({ picking = true }) else null,
        )
    }
    if (picking && actions != null) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Guide hier anheften") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    others.sortedWith(compareBy({ GuideTopics.orderOf(it.topic) }, { it.title.lowercase() })).forEach { g ->
                        TextButton(onClick = { actions.pin(g.id, target); picking = false }) { Text(g.title) }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Schließen") } },
        )
    }
}
