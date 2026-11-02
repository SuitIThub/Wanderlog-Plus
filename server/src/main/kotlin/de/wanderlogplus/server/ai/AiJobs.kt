package de.wanderlogplus.server.ai

import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.server.ServerEdit
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.Assessment
import de.wanderlogplus.shared.sync.DossierResult
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.JobStep
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.path.deleteRecursively

/** A reel job's input: a link, or a video already stored at [video] (uploaded by the app) with an optional caption. */
sealed interface ReelInput {
    data class Link(val url: String) : ReelInput
    data class Video(val video: Path, val caption: String?) : ReelInput
}

/**
 * Runs assistant jobs on background threads and keeps their state, log ([AiJobInfo.steps]) and per-place progress
 * ([AiJobInfo.items]) in the database, so the app can poll, watch, leave and come back. Without an [assistant]
 * (no OpenRouter key) new jobs fail with `ai_not_configured`.
 */
class AiJobs(
    private val store: SqliteStore,
    private val assistant: Assistant?,
    private val media: ReelMedia,
    private val workDir: Path,
    private val now: () -> Instant = Instant::now,
    // Two at a time: a long research of a whole region must not keep a shared reel waiting.
    private val executor: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "ai-jobs").apply { isDaemon = true } },
    /** Writes research results into the trip as the server (see SyncService.writeAsServer); null: the app applies them. */
    private val writeResults: ((List<ServerEdit>) -> Int)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger(AiJobs::class.java)

    val configured: Boolean get() = assistant != null

    fun get(id: String): AiJobInfo? = store.aiJob(id)?.let { json.decodeFromString(AiJobInfo.serializer(), it) }

    fun list(kind: String?, limit: Int): List<AiJobInfo> = store.aiJobs(kind, limit).map { json.decodeFromString(AiJobInfo.serializer(), it) }

    fun startReel(input: ReelInput, config: AssessConfig = AssessConfig()): AiJobInfo {
        val job = save(
            AiJobInfo(
                id = UUID.randomUUID().toString(),
                kind = "reel",
                createdAt = now().toEpochMilli(),
                status = "QUEUED",
                source = (input as? ReelInput.Link)?.url ?: "Video",
                steps = listOf(JobStep(now().toEpochMilli(), if (input is ReelInput.Link) "Reel-Link erhalten, wartet auf einen freien Platz …" else "Video erhalten, wartet auf einen freien Platz …")),
            ),
        )
        executor.execute { runReel(job, input, config) }
        return job
    }

    fun startAssessment(ids: List<String>, config: AssessConfig = AssessConfig()): AiJobInfo {
        val job = save(AiJobInfo(UUID.randomUUID().toString(), "assess", now().toEpochMilli(), "QUEUED", steps = listOf(queuedStep()), appliedOnServer = writeResults != null))
        executor.execute { runAssessment(job, ids, config) }
        return job
    }

    /** Web research of saved places (insider tips, hidden spots, criteria, verdict); [ids] must not be empty. */
    fun startResearch(ids: List<String>, config: AssessConfig = AssessConfig()): AiJobInfo {
        val job = save(
            AiJobInfo(
                UUID.randomUUID().toString(), "research", now().toEpochMilli(), "QUEUED", total = ids.size, steps = listOf(queuedStep()),
                appliedOnServer = writeResults != null, savedPlaceIds = ids, config = config,
            ),
        )
        executor.execute { runResearch(job, ids, config) }
        return job
    }

    private fun queuedStep() = JobStep(now().toEpochMilli(), "Auftrag angenommen, wartet auf einen freien Platz …")

    /**
     * Jobs a restart interrupted (still queued or running in the database): a reel from a link starts over, with the
     * same id so the app's watcher still finds it; a research continues with the places that have no result yet; an
     * uploaded video is gone and an assessment's input may have changed, so those are marked failed ("interrupted").
     * Finished research whose results were never written into the trip (older versions left that to the app) is
     * written now. Call once at startup.
     */
    fun resumeInterrupted() {
        val recent = list(null, 100)
        recent.filter { !(it.status == "DONE" || it.status == "FAILED") }.forEach { job ->
            val url = job.source?.takeIf { it.startsWith("https://") }
            if (job.kind == "research" && job.savedPlaceIds.isNotEmpty() && assistant != null) {
                val done = job.dossiers.map { it.savedPlaceId }.toSet()
                val rest = job.savedPlaceIds.filterNot { it in done }
                val restarted = save(
                    job.copy(
                        status = "QUEUED",
                        appliedOnServer = writeResults != null,
                        steps = (job.steps + JobStep(now().toEpochMilli(), "Server neu gestartet, Recherche geht mit den restlichen ${rest.size} Orten weiter", JobProgress.WARN)).takeLast(MAX_STEPS),
                    ),
                )
                executor.execute { runResearch(restarted, rest, job.config ?: AssessConfig()) }
            } else if (job.kind == "reel" && url != null && assistant != null) {
                val restarted = save(
                    job.copy(
                        status = "QUEUED", summary = null, candidates = emptyList(), items = emptyList(), progress = 0,
                        steps = (job.steps + JobStep(now().toEpochMilli(), "Server neu gestartet, Auswertung beginnt von vorn", JobProgress.WARN)).takeLast(MAX_STEPS),
                    ),
                )
                executor.execute { runReel(restarted, ReelInput.Link(url)) }
            } else {
                Tracker(job).fail("interrupted")
            }
        }
        if (writeResults != null) {
            recent.filter { it.kind == "research" && it.status == "DONE" && !it.appliedOnServer && it.dossiers.isNotEmpty() }.forEach { job ->
                val n = job.dossiers.count { writeDossier(it) }
                val line = JobStep(now().toEpochMilli(), "Nachgetragen: Ergebnisse von $n " + (if (n == 1) "Ort" else "Orten") + " in die Orte geschrieben", JobProgress.OK)
                save(job.copy(appliedOnServer = true, steps = (job.steps + line).takeLast(MAX_STEPS)))
            }
        }
    }

    /** Writes judgements into the trip: worth, verdict and the criteria (earlier research in the dossier is kept). */
    private fun writeAssessments(batch: List<Assessment>): Int {
        val write = writeResults ?: return 0
        val edits = batch.mapNotNull { r ->
            val saved = store.get(EntityType.SAVED_PLACE, r.savedPlaceId)?.takeIf { !it.isDeleted } ?: return@mapNotNull null
            val fields = mutableMapOf<String, JsonElement>("worth" to JsonPrimitive(r.worth.name), "worthReason" to JsonPrimitive(r.reason))
            if (r.criteria.isNotEmpty()) {
                val old = saved.fields["dossier"]?.value?.let { v ->
                    try { json.decodeFromJsonElement(PlaceDossier.serializer(), v) } catch (e: Exception) { null }
                } ?: PlaceDossier()
                fields["dossier"] = json.encodeToJsonElement(PlaceDossier.serializer(), old.copy(criteria = r.criteria, verdict = r.reason))
            }
            ServerEdit(EntityType.SAVED_PLACE, r.savedPlaceId, fields)
        }
        return if (edits.isEmpty()) 0 else write(edits)
    }

    /**
     * Writes one research result into the trip: dossier, worth, verdict and (only where empty) the place description.
     * A newer or the same research already stored is kept. Returns whether anything was written.
     */
    private fun writeDossier(r: DossierResult): Boolean {
        val write = writeResults ?: return false
        val saved = store.get(EntityType.SAVED_PLACE, r.savedPlaceId)?.takeIf { !it.isDeleted } ?: return false
        val storedAt = saved.fields["dossier"]?.value?.let { v ->
            try { json.decodeFromJsonElement(PlaceDossier.serializer(), v).researchedAt } catch (e: Exception) { null }
        } ?: 0L
        if (storedAt >= r.dossier.researchedAt) return false
        val fields = mutableMapOf<String, JsonElement>("dossier" to json.encodeToJsonElement(PlaceDossier.serializer(), r.dossier))
        r.worth?.let { fields["worth"] = JsonPrimitive(it.name) }
        if (r.dossier.verdict.isNotBlank()) fields["worthReason"] = JsonPrimitive(r.dossier.verdict)
        val edits = mutableListOf(ServerEdit(EntityType.SAVED_PLACE, r.savedPlaceId, fields))
        val placeId = (saved.fields["placeId"]?.value as? JsonPrimitive)?.contentOrNull
        if (placeId != null && r.description.isNotBlank()) {
            edits += ServerEdit(EntityType.PLACE, placeId, mapOf("description" to JsonPrimitive(r.description)), onlyIfEmpty = setOf("description"))
        }
        return write(edits) > 0
    }

    private fun save(job: AiJobInfo): AiJobInfo {
        store.putAiJob(job.id, job.kind, job.createdAt, json.encodeToString(AiJobInfo.serializer(), job))
        return job
    }

    /** One running job: every change (status, log, items, progress) goes through here, also from parallel research threads. */
    private inner class Tracker(start: AiJobInfo) : JobProgress {
        @Volatile var job: AiJobInfo = start
            private set

        @Synchronized
        fun update(change: (AiJobInfo) -> AiJobInfo): AiJobInfo {
            job = save(change(job))
            return job
        }

        override fun step(text: String, level: String) {
            update { it.copy(steps = (it.steps + JobStep(now().toEpochMilli(), text.take(500), level)).takeLast(MAX_STEPS)) }
        }

        override fun items(items: List<JobItem>) {
            // A resumed research keeps the places it already finished.
            update { j -> j.copy(items = j.items.filter { old -> items.none { it.key == old.key } } + items) }
        }

        override fun item(key: String, state: String?, detail: String?) {
            val t = now().toEpochMilli()
            update { j ->
                j.copy(
                    items = j.items.map { i ->
                        if (i.key != key) {
                            i
                        } else {
                            i.copy(
                                state = state ?: i.state,
                                detail = detail?.take(300) ?: i.detail,
                                startedAt = if (state == "RUNNING" && i.startedAt == 0L) t else i.startedAt,
                                finishedAt = if (state == "DONE" || state == "FAILED") t else i.finishedAt,
                            )
                        }
                    },
                )
            }
        }

        fun status(s: String) = update { it.copy(status = s) }

        fun done(change: (AiJobInfo) -> AiJobInfo) {
            step("Fertig", JobProgress.OK)
            update { change(it).copy(status = "DONE", finishedAt = now().toEpochMilli()) }
        }

        fun fail(code: String) {
            step("Abgebrochen: " + failText(code), JobProgress.ERROR)
            update { it.copy(status = "FAILED", error = code, finishedAt = now().toEpochMilli()) }
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun runReel(start: AiJobInfo, input: ReelInput, config: AssessConfig = AssessConfig()) {
        val t = Tracker(start)
        val a = assistant ?: run { t.fail("ai_not_configured"); return }
        val dir = Files.createTempDirectory(workDir.also { Files.createDirectories(it) }, "reel-")
        try {
            t.status("DOWNLOADING")
            val (video, caption) = when (input) {
                is ReelInput.Link -> {
                    t.step("Video wird heruntergeladen (yt-dlp) …")
                    media.download(input.url, dir).let { it.video to it.caption }
                }
                is ReelInput.Video -> input.video to input.caption
            }
            t.step(
                "Video bereit, " + (caption?.let { "Beschreibung mit ${it.length} Zeichen" } ?: "ohne Beschreibung") +
                    ", " + sizeText(video),
                JobProgress.OK,
            )
            t.status("ANALYZING")
            t.step("Bilder und Ton werden herausgelöst (ffmpeg) …")
            val extracted = media.extract(video, dir)
            t.step("${extracted.frames.size} Bilder" + if (extracted.audio != null) " und Tonspur" else ", keine Tonspur", JobProgress.OK)
            val context = TripContext.from(store)
            t.step("KI erkennt Orte in Bild, Ton und Beschreibung …")
            val (summary, found) = a.findPlaces(caption, extracted, context)
            summary?.let { t.step("Inhalt: $it") }
            if (found.isEmpty()) {
                t.step("Kein konkreter Ort erkannt", JobProgress.WARN)
            } else {
                t.step("${found.size} " + (if (found.size == 1) "Ort" else "Orte") + " erkannt: " + found.joinToString(", ") { it.name }, JobProgress.OK)
            }
            t.update { it.copy(status = "RESEARCHING", summary = summary, total = found.size) }
            if (found.isNotEmpty()) t.step("Recherche startet, bis zu ${minOf(found.size, RESEARCH_PARALLELISM)} Orte gleichzeitig")
            val candidates = a.researchAll(found, context, config, t) { n -> t.update { it.copy(progress = n) } }
            t.done { it.copy(candidates = candidates, progress = found.size) }
        } catch (e: DownloadFailed) {
            t.fail("download_failed")
        } catch (e: AiFailed) {
            t.fail(e.code)
        } catch (e: Exception) {
            // Class name only: messages may contain reel or trip content.
            log.error("Reel job failed: {}", e.javaClass.simpleName)
            t.fail("internal")
        } finally {
            try { dir.deleteRecursively() } catch (_: Exception) {}
            if (input is ReelInput.Video) try { Files.deleteIfExists(input.video) } catch (_: Exception) {}
        }
    }

    private fun runAssessment(start: AiJobInfo, ids: List<String>, config: AssessConfig) {
        val t = Tracker(start)
        val a = assistant ?: run { t.fail("ai_not_configured"); return }
        try {
            t.status("ASSESSING")
            t.step("Einschätzung nach ${config.criteria.size} Kriterien, ohne Websuche")
            var written = 0
            val results = a.assess(store, ids, config, t) { batch -> written += writeAssessments(batch) }
            if (writeResults != null) t.step("$written " + (if (written == 1) "Ort" else "Orte") + " mit der Einschätzung aktualisiert", JobProgress.OK)
            t.done { it.copy(assessments = results) }
        } catch (e: AiFailed) {
            t.fail(e.code)
        } catch (e: Exception) {
            log.error("Assessment job failed: {}", e.javaClass.simpleName)
            t.fail("internal")
        }
    }

    private fun runResearch(start: AiJobInfo, ids: List<String>, config: AssessConfig) {
        val t = Tracker(start)
        val a = assistant ?: run { t.fail("ai_not_configured"); return }
        try {
            t.status("RESEARCHING")
            t.step(
                "Recherche für ${ids.size} " + (if (ids.size == 1) "Ort" else "Orte") + " nach ${config.criteria.size} Kriterien" +
                    (if (config.extraPrompt.isNotBlank()) " mit eigenen Anweisungen" else "") +
                    ", bis zu ${minOf(ids.size, RESEARCH_PARALLELISM)} gleichzeitig",
            )
            val before = start.total - ids.size // finished before a restart
            val written = java.util.concurrent.atomic.AtomicInteger()
            a.researchSaved(
                store, ids, config, { now().toEpochMilli() }, t,
                onResult = { r ->
                    t.update { j -> j.copy(dossiers = j.dossiers.filterNot { it.savedPlaceId == r.savedPlaceId } + r) }
                    if (writeDossier(r)) written.incrementAndGet()
                },
            ) { n -> t.update { it.copy(progress = before + n) } }
            if (writeResults != null) t.step("${written.get()} " + (if (written.get() == 1) "Ort" else "Orte") + " mit den Ergebnissen aktualisiert", JobProgress.OK)
            t.done { it.copy(progress = it.total) }
        } catch (e: AiFailed) {
            t.fail(e.code)
        } catch (e: Exception) {
            log.error("Research job failed: {}", e.javaClass.simpleName)
            t.fail("internal")
        }
    }

    private fun sizeText(video: Path): String = try {
        "%.1f MB".format(java.util.Locale.GERMAN, Files.size(video) / 1_048_576.0)
    } catch (e: Exception) {
        "Größe unbekannt"
    }

    /** Waits for queued work (tests). */
    internal fun drain() {
        executor.submit {}.get()
    }

    companion object {
        const val MAX_STEPS = 300

        /** Short German reason for the log's last line (the app explains [code] in full). */
        fun failText(code: String): String = when (code) {
            "download_failed" -> "Reel ließ sich nicht laden"
            "ai_not_configured" -> "kein OpenRouter-Key"
            "ai_key_rejected", "research_key_rejected" -> "Key abgelehnt"
            "ai_no_credit", "research_no_credit" -> "Guthaben aufgebraucht"
            "ai_unreachable" -> "KI nicht erreichbar"
            "research_timeout" -> "Recherche dauerte zu lange"
            "interrupted" -> "Server wurde neu gestartet"
            else -> "Fehler ($code)"
        }
    }
}
