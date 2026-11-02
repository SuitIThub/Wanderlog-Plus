package de.wanderlogplus.app.ui.reels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.JobStep
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val JOB_ROUTE = "mehr/ki-ablauf/{jobId}"
fun jobRoute(jobId: String) = "mehr/ki-ablauf/${android.net.Uri.encode(jobId)}"

const val JOBS_ROUTE = "mehr/ki-auftraege"

/** "Reel-Auswertung", "Recherche", "Einschätzung". */
fun jobKindLabel(kind: String): String = when (kind) {
    "reel" -> "Reel-Auswertung"
    "research" -> "Recherche"
    "assess" -> "Einschätzung"
    else -> "KI-Auftrag"
}

/** "0:42", "12:05", "1:02:03". */
fun durationText(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** "läuft seit 1:23" / "Dauer 2:41" (from creation to the end). */
fun elapsedText(job: AiJobInfo, now: Long): String =
    if (job.finishedAt > 0) "Dauer " + durationText(job.finishedAt - job.createdAt) else "läuft seit " + durationText(now - job.createdAt)

/** "3 von 8 Orten fertig" while places are known; null otherwise. */
fun itemsSummary(items: List<JobItem>): String? {
    if (items.isEmpty()) return null
    val done = items.count { it.state == "DONE" || it.state == "FAILED" }
    val running = items.count { it.state == "RUNNING" }
    return "$done von ${items.size} " + (if (items.size == 1) "Ort" else "Orten") + " fertig" + if (running > 0) " · $running in Arbeit" else ""
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMAN)

/**
 * "Ablauf": watches a reel analysis, research or judgement live: status, time, progress, every place with its
 * current step and the full log (newest on top). [onOpenResult] (a finished reel) leads to the results.
 * Tags: `job-progress`, `job-status`, `job-item-<key>`, `job-log`, `job-result`.
 */
@Composable
fun JobProgressScreen(
    job: AiJobInfo?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenResult: (() -> Unit)? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    var now by remember { mutableLongStateOf(clock()) }
    val running = job != null && !isFinished(job)
    LaunchedEffect(running) {
        while (running) {
            now = clock()
            delay(1_000)
        }
    }
    Column(modifier.fillMaxSize().testTag("job-progress")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text("Ablauf" + (job?.let { " · " + jobKindLabel(it.kind) } ?: ""), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (job == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "head") {
                SurfaceCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(statusLabel(job.status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("job-status"))
                            Text(elapsedText(job, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        job.source?.takeIf { job.kind == "reel" }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        job.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        when {
                            job.status == "FAILED" -> Text(errorText(job.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            running && job.total > 0 -> LinearProgressIndicator(progress = { job.progress.toFloat() / job.total }, modifier = Modifier.fillMaxWidth())
                            running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        itemsSummary(job.items)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (onOpenResult != null && job.status == "DONE") {
                            Button(onClick = onOpenResult, modifier = Modifier.fillMaxWidth().testTag("job-result")) { Text("Ergebnis ansehen") }
                        }
                    }
                }
            }
            if (job.items.isNotEmpty()) {
                item(key = "items-label") { SectionLabel("Orte") }
                item(key = "items") {
                    SurfaceCard {
                        Column {
                            job.items.forEachIndexed { i, it ->
                                if (i > 0) HorizontalDivider()
                                ItemRow(it, now)
                            }
                        }
                    }
                }
            }
            item(key = "log-label") { SectionLabel("Protokoll (neueste oben)") }
            if (job.steps.isEmpty()) {
                item(key = "log-empty") { EmptyHint("Noch keine Einträge") }
            }
            itemsIndexed(job.steps.asReversed(), key = { i, _ -> "log-${job.steps.size - 1 - i}" }) { _, s -> LogRow(s) }
        }
    }
}

@Composable
private fun ItemRow(item: JobItem, now: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).testTag("job-item-${item.key}"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (item.state) {
                "RUNNING" -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                "DONE" -> Icon(Icons.Filled.CheckCircle, contentDescription = "fertig", tint = Color(0xFF2E7D32))
                "FAILED" -> Icon(Icons.Filled.Error, contentDescription = "ohne Ergebnis", tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Filled.RadioButtonUnchecked, contentDescription = "wartet", tint = MaterialTheme.colorScheme.outline)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = item.detail.ifBlank { if (item.state == "WAITING") "wartet" else "" }
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val time = when {
            item.finishedAt > 0 && item.startedAt > 0 -> durationText(item.finishedAt - item.startedAt)
            item.startedAt > 0 -> durationText(now - item.startedAt)
            else -> null
        }
        time?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) }
    }
}

@Composable
private fun LogRow(step: JobStep) {
    val color = when (step.level) {
        "OK" -> Color(0xFF2E7D32)
        "WARN" -> Color(0xFFB26A00)
        "ERROR" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().testTag("job-log")) {
        Text(
            timeFormat.format(Date(step.at)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp).padding(top = 2.dp),
        )
        Text(step.text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

/** "KI-Aufträge": recent reel analyses, researches and judgements; a tap opens their Ablauf. Tag `ai-job-<id>`. */
@Composable
fun AiJobsScreen(jobs: List<AiJobInfo>, onOpen: (jobId: String) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, clock: () -> Long = System::currentTimeMillis) {
    Column(modifier.fillMaxSize().testTag("ai-jobs")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text("KI-Aufträge", style = MaterialTheme.typography.titleLarge)
        }
        if (jobs.isEmpty()) {
            EmptyHint("Noch keine KI-Aufträge", Modifier.padding(16.dp))
            return@Column
        }
        val now = clock()
        val dateFormat = remember { SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(jobs, key = { it.id }) { j ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(j.id) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("ai-job-${j.id}")) {
                    Text(
                        jobKindLabel(j.kind) + (j.summary?.let { " · $it" } ?: if (j.total > 0) " · ${j.total} " + (if (j.total == 1) "Ort" else "Orte") else ""),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        dateFormat.format(Date(j.createdAt)) + " · " + statusLabel(j.status) + " · " + elapsedText(j, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (j.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
