package de.wanderlogplus.app.ui.guides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.ChatCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The recent guide jobs from the server (also those the chat started), newest first; empty where unknown. */
val LocalGuideJobs = compositionLocalOf<List<AiJobInfo>> { emptyList() }

/** What a guide job means for one guide right now. */
sealed interface GuideJobState {
    data class Running(val job: AiJobInfo) : GuideJobState
    data class Failed(val job: AiJobInfo, val detail: String) : GuideJobState
}

object GuideJobs {
    /** How long a failed rewrite stays noted on its guide. */
    private const val FAILED_NOTE_MS = 24 * 3_600_000L

    fun isActive(job: AiJobInfo) = job.status != "DONE" && job.status != "FAILED"

    private fun failure(job: AiJobInfo): String? = when {
        job.status == "FAILED" -> job.error ?: "fehlgeschlagen"
        else -> job.items.firstOrNull { it.state == "FAILED" }?.let { it.detail.ifBlank { "fehlgeschlagen" } }
    }

    /**
     * A running job that rewrites [guide], or the last rewrite that failed and was not followed by a newer text
     * (noted for a day); null when nothing is going on.
     */
    fun stateOf(guide: Guide, jobs: List<AiJobInfo>, now: Long = System.currentTimeMillis()): GuideJobState? {
        val mine = jobs.filter { guide.id in it.guideIds }.sortedByDescending { it.createdAt }
        mine.firstOrNull(::isActive)?.let { return GuideJobState.Running(it) }
        val last = mine.firstOrNull() ?: return null
        val failed = failure(last) ?: return null
        if (guide.writtenAt >= last.createdAt || now - last.createdAt > FAILED_NOTE_MS) return null
        return GuideJobState.Failed(last, failed)
    }

    /** Jobs that ended with a failure in the last day, for the overview. */
    fun recentFailures(jobs: List<AiJobInfo>, now: Long = System.currentTimeMillis()): List<Pair<AiJobInfo, String>> =
        jobs.filter { !isActive(it) && now - it.createdAt <= FAILED_NOTE_MS }.mapNotNull { j -> failure(j)?.let { j to it } }

    /** "6. Nov. 2026, 17:58" in the phone's time zone. */
    fun writtenText(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", Locale.GERMAN).format(Instant.ofEpochMilli(millis).atZone(zone))

    /** The names a job works on, for a one-line title. */
    fun names(job: AiJobInfo): String = job.items.joinToString(", ") { it.name }.ifBlank { "Guide" }
}

/** Running job or failed rewrite of one guide, shown on its page. */
@Composable
fun GuideJobBanner(state: GuideJobState) {
    when (state) {
        is GuideJobState.Running -> SurfaceCard(Modifier.testTag("guide-job-running")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Wird gerade überarbeitet …", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { if (state.job.total == 0) 0f else state.job.progress / state.job.total.toFloat() }, Modifier.fillMaxWidth())
                state.job.steps.lastOrNull()?.let { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                Text("Der neue Text erscheint hier, sobald er fertig ist.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is GuideJobState.Failed -> SurfaceCard(Modifier.testTag("guide-job-failed")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Überarbeitung fehlgeschlagen", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(state.detail + ". Der bisherige Text ist unverändert.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A chat card for a guide job: its live state, and a tap opens the guide (or the overview while it is not written yet). */
@Composable
fun ChatGuideCard(c: ChatCard) {
    val jobs = LocalGuideJobs.current
    val job = c.jobId?.let { id -> jobs.firstOrNull { it.id == id } }
    val actions = LocalGuideActions.current
    LaunchedEffect(c.jobId) { actions?.refreshJobs() }
    val openGuide = LocalOpenGuide.current
    val openGuides = LocalOpenGuides.current
    val target = c.guideId ?: job?.takeIf { it.status == "DONE" }?.guideIds?.firstOrNull()
    val failed = job?.let { j -> if (GuideJobs.isActive(j)) null else (if (j.status == "FAILED") j.error ?: "fehlgeschlagen" else j.items.firstOrNull { it.state == "FAILED" }?.detail?.ifBlank { "fehlgeschlagen" }) }
    val state = when {
        job == null -> c.subtitle ?: "Läuft im Hintergrund"
        GuideJobs.isActive(job) -> (c.subtitle ?: "Läuft") + " · " + (job.steps.lastOrNull()?.text ?: "wartet")
        failed != null -> "Fehlgeschlagen: $failed"
        else -> "Fertig – antippen zum Öffnen"
    }
    Card(
        onClick = { if (target != null && openGuide != null) openGuide(target) else openGuides?.invoke() },
        modifier = Modifier.fillMaxWidth().testTag("chat-guide-card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text("Guide: " + c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    state, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (job == null || GuideJobs.isActive(job)) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
