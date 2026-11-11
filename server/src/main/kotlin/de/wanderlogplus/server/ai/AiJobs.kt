package de.wanderlogplus.server.ai

import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.server.ServerEdit
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.guide.GuideMatch
import de.wanderlogplus.shared.guide.GuideTopics
import de.wanderlogplus.shared.sync.GuideAsk
import de.wanderlogplus.shared.sync.AssessConfig
import de.wanderlogplus.shared.sync.Assessment
import de.wanderlogplus.shared.sync.DossierResult
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.ReelTip
import de.wanderlogplus.shared.sync.TipVerdict
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
    /**
     * Research jobs run here, one after another: a second research waits for the first instead of both sharing the
     * places' parallelism and the app's progress line. Reels, guides and stays keep the pool above.
     */
    private val researchExecutor: ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "ai-research").apply { isDaemon = true } },
    /** Writes research results into the trip as the server (see SyncService.writeAsServer); null: the app applies them. */
    private val writeResults: ((List<ServerEdit>) -> Int)? = null,
    /** Writes travel guides; null: guide jobs fail with `ai_not_configured`. */
    private val guideWriter: GuideWriter? = null,
    /** Researches room and amenities of stays; null: stay jobs fail with `ai_not_configured`. */
    private val stayResearch: StayResearch? = null,
    /** Reads the stays' confirmations and keeps room photos. */
    private val files: de.wanderlogplus.server.FileStore? = null,
    /** How long a research waits for places it does not know yet (just added in the app, sync still on its way). */
    private val arrivalWaitMillis: Long = 60_000,
    /** Matches and checks the travel tips of a reel; null: tips stay unchecked (UNVERIFIED). */
    private val tipCheck: TipCheck? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger(AiJobs::class.java)

    val configured: Boolean get() = assistant != null

    /** Threads of the research lane (1 by default); -1 when unknown (a custom executor). */
    val researchLaneSize: Int get() = (researchExecutor as? java.util.concurrent.ThreadPoolExecutor)?.maximumPoolSize ?: -1

    /** Research jobs queued or running in the lane. */
    private val researchActive = java.util.concurrent.atomic.AtomicInteger()

    /** Puts a research job into the lane; the counter says whether the next one has to wait. */
    private fun enqueueResearch(run: () -> Unit) {
        researchActive.incrementAndGet()
        try {
            researchExecutor.execute { try { run() } finally { researchActive.decrementAndGet() } }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            researchActive.decrementAndGet()
            throw e
        }
    }

    val guidesConfigured: Boolean get() = guideWriter != null

    val staysConfigured: Boolean get() = stayResearch != null

    /** Researches the room and amenities of a stay and writes them (hand edits stay unless [StayResearchRequest.fresh]). */
    fun startStay(req: de.wanderlogplus.shared.sync.StayResearchRequest): AiJobInfo {
        val job = save(
            AiJobInfo(
                UUID.randomUUID().toString(), "stay", now().toEpochMilli(), "QUEUED", total = 1, steps = listOf(queuedStep()),
                appliedOnServer = writeResults != null, stayId = req.stayId,
            ),
        )
        executor.execute { runStay(job, req) }
        return job
    }

    private fun runStay(start: AiJobInfo, req: de.wanderlogplus.shared.sync.StayResearchRequest) {
        val t = Tracker(start)
        val r = stayResearch ?: run { t.fail("ai_not_configured"); return }
        try {
            t.status("RESEARCHING")
            val record = store.get(EntityType.STAY, req.stayId)?.takeIf { !it.isDeleted } ?: run { t.fail("not_found"); return }
            val stay = EntityCodec.decode<de.wanderlogplus.shared.model.Stay>(record)
            val place = store.get(EntityType.PLACE, stay.placeId)?.takeIf { !it.isDeleted }?.let { EntityCodec.decode<de.wanderlogplus.shared.model.Place>(it) }
            val attachments = store.all(EntityType.ATTACHMENT).filter { !it.isDeleted }
                .mapNotNull { a -> try { EntityCodec.decode<de.wanderlogplus.shared.model.Attachment>(a) } catch (e: Exception) { null } }
                .filter { it.targetType == EntityType.STAY && it.targetId == stay.id }
            val docs = attachments.filter { it.kind != "photo" && (it.mimeType == "application/pdf" || it.mimeType.startsWith("image/")) }
                .mapNotNull { a -> files?.open(a.fileHash)?.let { de.wanderlogplus.server.ai.StayDocument(a.mimeType, java.nio.file.Files.readAllBytes(it), a.fileName) } }
            val links = req.links.map { it.trim() }.filter { it.startsWith("http") }.ifEmpty { stay.infoLinks }
            t.step("${docs.size} Bestätigung(en), ${links.size} Link(s)")
            val found = r.research(stay, place, docs, links) { t.step(it) }
            if (found == null) { t.fail("research_empty"); return }
            val old = stay.info?.let { if (req.fresh) it.copy(editedByHand = emptySet()) else it }
            val merged = de.wanderlogplus.shared.model.StayInfoMerge.merge(old, found.copy(researchedAt = now().toEpochMilli()))
            val fields = mapOf(
                "info" to json.encodeToJsonElement(de.wanderlogplus.shared.model.StayInfo.serializer(), merged),
                "infoLinks" to kotlinx.serialization.json.JsonArray(links.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            )
            writeResults?.invoke(listOf(ServerEdit(EntityType.STAY, stay.id, fields)))
            val saved = saveRoomPhotos(r, stay.id, links, merged.room.type, attachments.map { it.fileHash }.toSet())
            if (saved > 0) t.step("$saved Zimmerfoto(s) übernommen", JobProgress.OK)
            t.done { it.copy(progress = 1) }
        } catch (e: AiFailed) {
            t.fail(e.code)
        } catch (e: Exception) {
            log.error("Stay research failed: {}", e.javaClass.simpleName)
            t.fail("internal")
        }
    }

    /** Pictures of the room type from the links as "room" photos of the stay; failures only cost the photos. */
    private fun saveRoomPhotos(r: StayResearch, stayId: String, links: List<String>, roomType: String, known: Set<String>): Int {
        val source = r.photos ?: return 0
        val store = files ?: return 0
        val write = writeResults ?: return 0
        val seen = known.toMutableSet()
        var n = 0
        links.forEach { url ->
            val got = try { source.photos(url, roomType) } catch (e: Exception) { emptyList() }
            got.forEach { (name, bytes) ->
                if (n >= HttpRoomPhotoSource.MAX) return@forEach
                val stored = try { store.save(java.io.ByteArrayInputStream(bytes)) } catch (e: Exception) { return@forEach }
                if (!seen.add(stored.hash)) return@forEach
                val mime = when (name.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
                val a = de.wanderlogplus.shared.model.Attachment(
                    UUID.randomUUID().toString(), EntityType.STAY, stayId, stored.hash, name, mime, stored.size,
                    kind = "photo", label = de.wanderlogplus.shared.model.Attachment.LABEL_ROOM,
                )
                if (write(listOf(ServerEdit(EntityType.ATTACHMENT, a.id, EntityCodec.fields(a), create = true))) > 0) n++
            }
        }
        return n
    }

    /** Writes or rewrites travel guides one after the other; a failed one does not stop the rest. */
    fun startGuides(asks: List<GuideAsk>): AiJobInfo {
        val job = save(
            AiJobInfo(
                UUID.randomUUID().toString(), "guides", now().toEpochMilli(), "QUEUED", total = asks.size, steps = listOf(queuedStep()),
                appliedOnServer = writeResults != null,
                // Guides being rewritten are known from the start, so the app can mark them while the job runs.
                guideIds = asks.mapNotNull { it.guideId }.distinct(),
            ),
        )
        executor.execute { runGuides(job, asks) }
        return job
    }

    private fun runGuides(start: AiJobInfo, asks: List<GuideAsk>) {
        val t = Tracker(start)
        val w = guideWriter ?: run { t.fail("ai_not_configured"); return }
        try {
            t.status("RESEARCHING")
            val keyed = asks.mapIndexed { i, a -> "guide-$i" to a }
            fun label(a: GuideAsk) = GuideTopics.byKey(a.topic)?.name ?: a.title.ifBlank { "Guide" }
            t.items(keyed.map { (k, a) -> JobItem(k, label(a)) })
            keyed.forEachIndexed { i, (key, ask) ->
                val say = t.sayFor(key, label(ask))
                t.item(key, state = "RUNNING")
                try {
                    val trip = ChatTrip.from(store)
                    val existing = ask.guideId?.let { id -> trip.guides.firstOrNull { it.id == id } }
                        ?: GuideMatch.duplicateOf(ask.topic, ask.title, trip.guides)
                    val g = w.write(ask, GuideWriter.tripContext(trip, ask.topic ?: existing?.topic), existing, say)
                        .copy(writtenAt = now().toEpochMilli())
                    writeResults?.invoke(listOf(ServerEdit(EntityType.GUIDE, g.id, EntityCodec.fields(g), create = true)))
                    t.update { j -> j.copy(guideIds = (j.guideIds + g.id).distinct()) }
                    t.item(key, state = "DONE", detail = g.title)
                    t.step("${label(ask)}: fertig", JobProgress.OK)
                } catch (e: AiFailed) {
                    t.item(key, state = "FAILED", detail = failText(e.code))
                    t.step("${label(ask)}: " + failText(e.code), JobProgress.WARN)
                } catch (e: Exception) {
                    // One guide that cannot be saved must not stop the rest.
                    log.error("Guide failed: {}", e.javaClass.simpleName)
                    t.item(key, state = "FAILED", detail = failText("internal"))
                    t.step("${label(ask)}: " + failText("internal"), JobProgress.WARN)
                }
                t.update { it.copy(progress = i + 1) }
            }
            t.done { it.copy(progress = it.total) }
        } catch (e: Exception) {
            log.error("Guide job failed: {}", e.javaClass.simpleName)
            t.fail("internal")
        }
    }

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
                UUID.randomUUID().toString(), "research", now().toEpochMilli(), "QUEUED", total = ids.size,
                steps = listOf(if (researchActive.get() > 0) JobStep(now().toEpochMilli(), "Wartet auf die laufende Recherche …") else queuedStep()),
                appliedOnServer = writeResults != null, savedPlaceIds = ids, config = config,
            ),
        )
        enqueueResearch { runResearch(job, ids, config) }
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
        // Oldest first, so interrupted research keeps its order in the lane.
        recent.filter { !(it.status == "DONE" || it.status == "FAILED") }.sortedBy { it.createdAt }.forEach { job ->
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
                enqueueResearch { runResearch(restarted, rest, job.config ?: AssessConfig()) }
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
            val downloaded = when (input) {
                is ReelInput.Link -> {
                    t.step("Beitrag wird heruntergeladen (yt-dlp, für Bilder gallery-dl) …")
                    media.download(input.url, dir)
                }
                is ReelInput.Video -> Downloaded(listOf(input.video), emptyList(), input.caption)
            }
            val caption = downloaded.caption
            t.step(
                "Beitrag bereit: " + countText(downloaded.images.size, "Bild", "Bilder") + ", " + countText(downloaded.videos.size, "Video", "Videos") +
                    ", " + (caption?.let { "Beschreibung mit ${it.length} Zeichen" } ?: "ohne Beschreibung") +
                    downloaded.videos.firstOrNull()?.let { ", " + sizeText(it) }.orEmpty(),
                JobProgress.OK,
            )
            t.status("ANALYZING")
            t.step("Bilder und Ton werden aufbereitet (ffmpeg) …")
            val extracted = media.extract(downloaded, dir)
            t.step(countText(extracted.frames.size, "Bild", "Bilder") + if (extracted.audio != null) " und Tonspur" else ", keine Tonspur", JobProgress.OK)
            val context = TripContext.from(store)
            t.step("KI erkennt Orte und Tipps in Bild, Ton und Beschreibung …")
            val analysis = a.analyze(caption, extracted, context)
            val summary = analysis.summary
            val found = analysis.places
            summary?.let { t.step("Inhalt: $it") }
            if (found.isEmpty()) {
                t.step("Kein konkreter Ort erkannt", if (analysis.tips.isEmpty()) JobProgress.WARN else JobProgress.INFO)
            } else {
                t.step("${found.size} " + (if (found.size == 1) "Ort" else "Orte") + " erkannt: " + found.joinToString(", ") { it.name }, JobProgress.OK)
            }
            if (analysis.tips.isNotEmpty()) t.step(countText(analysis.tips.size, "allgemeiner Tipp", "allgemeine Tipps") + " erkannt", JobProgress.OK)
            t.update { it.copy(status = "RESEARCHING", summary = summary, total = found.size) }
            if (found.isNotEmpty()) t.step("Recherche startet, bis zu ${minOf(found.size, a.parallelism)} Orte gleichzeitig")
            val candidates = a.researchAll(found, context, config, t) { n -> t.update { it.copy(progress = n) } }
            val tips = when {
                analysis.tips.isEmpty() -> emptyList()
                tipCheck == null -> analysis.tips.map {
                    ReelTip(it.claim, it.topic, it.evidence, verdict = TipVerdict.UNVERIFIED, text = it.claim, reason = "Keine Prüfung eingerichtet", targetTitle = it.topic.ifBlank { "Reisetipps" })
                }
                else -> {
                    t.status("CHECKING")
                    t.step("Tipps werden mit den Guides abgeglichen und geprüft …")
                    tipCheck.checkAll(analysis.tips, ChatTrip.from(store).guides, t).also { r ->
                        val covered = r.count { it.coveredBy != null }
                        val wrong = r.count { it.verdict == TipVerdict.FALSE }
                        t.step("Tipps geprüft: ${r.size - covered - wrong} übernehmbar, $covered schon in Guides, $wrong falsch", JobProgress.OK)
                    }
                }
            }
            t.done { it.copy(candidates = candidates, tips = tips, progress = found.size) }
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
            val waitUntil = System.currentTimeMillis() + arrivalWaitMillis
            fun missing() = ids.count { id -> store.get(EntityType.SAVED_PLACE, id)?.takeIf { !it.isDeleted } == null }
            if (missing() > 0) {
                t.step("Wartet, bis neue Orte vom Handy angekommen sind …")
                while (missing() > 0 && System.currentTimeMillis() < waitUntil) Thread.sleep(2_000)
            }
            t.step(
                "Recherche für ${ids.size} " + (if (ids.size == 1) "Ort" else "Orte") + " nach ${config.criteria.size} Kriterien" +
                    (if (config.extraPrompt.isNotBlank()) " mit eigenen Anweisungen" else "") +
                    ", bis zu ${minOf(ids.size, a.parallelism)} gleichzeitig",
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

    private fun countText(n: Int, one: String, many: String) = "$n " + if (n == 1) one else many

    private fun sizeText(video: Path): String = try {
        "%.1f MB".format(java.util.Locale.GERMAN, Files.size(video) / 1_048_576.0)
    } catch (e: Exception) {
        "Größe unbekannt"
    }

    /** Waits for queued work (tests). */
    internal fun drain() {
        researchExecutor.submit {}.get()
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
            "research_empty" -> "Recherche ohne Ergebnis"
            "not_found" -> "Unterkunft nicht gefunden"
            "ai_bad_answer" -> "KI-Antwort unbrauchbar"
            "interrupted" -> "Server wurde neu gestartet"
            else -> "Fehler ($code)"
        }
    }
}
