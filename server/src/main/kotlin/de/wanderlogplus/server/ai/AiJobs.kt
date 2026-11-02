package de.wanderlogplus.server.ai

import de.wanderlogplus.server.SqliteStore
import de.wanderlogplus.shared.sync.AiJobInfo
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
 * Runs assistant jobs one after another on a background thread and keeps their state in the database, so the app can
 * poll, leave and come back. Without an [assistant] (no OpenRouter key) new jobs fail with `ai_not_configured`.
 */
class AiJobs(
    private val store: SqliteStore,
    private val assistant: Assistant?,
    private val media: ReelMedia,
    private val workDir: Path,
    private val now: () -> Instant = Instant::now,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-jobs").apply { isDaemon = true } },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger(AiJobs::class.java)

    val configured: Boolean get() = assistant != null

    fun get(id: String): AiJobInfo? = store.aiJob(id)?.let { json.decodeFromString(AiJobInfo.serializer(), it) }

    fun list(kind: String?, limit: Int): List<AiJobInfo> = store.aiJobs(kind, limit).map { json.decodeFromString(AiJobInfo.serializer(), it) }

    fun startReel(input: ReelInput): AiJobInfo {
        val job = save(
            AiJobInfo(
                id = UUID.randomUUID().toString(),
                kind = "reel",
                createdAt = now().toEpochMilli(),
                status = "QUEUED",
                source = (input as? ReelInput.Link)?.url ?: "Video",
            ),
        )
        executor.execute { runReel(job, input) }
        return job
    }

    fun startAssessment(ids: List<String>): AiJobInfo {
        val job = save(AiJobInfo(UUID.randomUUID().toString(), "assess", now().toEpochMilli(), "QUEUED"))
        executor.execute { runAssessment(job, ids) }
        return job
    }

    /**
     * Jobs a restart interrupted (still queued or running in the database): a reel from a link starts over, with the
     * same id so the app's watcher still finds it; an uploaded video is gone and an assessment's input may have
     * changed, so those are marked failed ("interrupted"). Call once at startup.
     */
    fun resumeInterrupted() {
        list(null, 100).filter { !(it.status == "DONE" || it.status == "FAILED") }.forEach { job ->
            val url = job.source?.takeIf { it.startsWith("https://") }
            if (job.kind == "reel" && url != null && assistant != null) {
                val restarted = save(job.copy(status = "QUEUED", summary = null, candidates = emptyList()))
                executor.execute { runReel(restarted, ReelInput.Link(url)) }
            } else {
                fail(job, "interrupted")
            }
        }
    }

    private fun save(job: AiJobInfo): AiJobInfo {
        store.putAiJob(job.id, job.kind, job.createdAt, json.encodeToString(AiJobInfo.serializer(), job))
        return job
    }

    private fun fail(job: AiJobInfo, code: String) = save(job.copy(status = "FAILED", error = code))

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun runReel(start: AiJobInfo, input: ReelInput) {
        var job = start
        val a = assistant ?: run { fail(job, "ai_not_configured"); return }
        val dir = Files.createTempDirectory(workDir.also { Files.createDirectories(it) }, "reel-")
        try {
            job = save(job.copy(status = "DOWNLOADING"))
            val (video, caption) = when (input) {
                is ReelInput.Link -> media.download(input.url, dir).let { it.video to it.caption }
                is ReelInput.Video -> input.video to input.caption
            }
            job = save(job.copy(status = "ANALYZING"))
            val extracted = media.extract(video, dir)
            val context = TripContext.from(store)
            val (summary, found) = a.findPlaces(caption, extracted, context)
            job = save(job.copy(status = "RESEARCHING", summary = summary))
            val candidates = found.map { a.research(it, context) }
            save(job.copy(status = "DONE", candidates = candidates))
        } catch (e: DownloadFailed) {
            fail(job, "download_failed")
        } catch (e: AiFailed) {
            fail(job, e.code)
        } catch (e: Exception) {
            // Class name only: messages may contain reel or trip content.
            log.error("Reel job failed: {}", e.javaClass.simpleName)
            fail(job, "internal")
        } finally {
            try { dir.deleteRecursively() } catch (_: Exception) {}
            if (input is ReelInput.Video) try { Files.deleteIfExists(input.video) } catch (_: Exception) {}
        }
    }

    private fun runAssessment(start: AiJobInfo, ids: List<String>) {
        var job = start
        val a = assistant ?: run { fail(job, "ai_not_configured"); return }
        try {
            job = save(job.copy(status = "ASSESSING"))
            save(job.copy(status = "DONE", assessments = a.assess(store, ids)))
        } catch (e: AiFailed) {
            fail(job, e.code)
        } catch (e: Exception) {
            log.error("Assessment job failed: {}", e.javaClass.simpleName)
            fail(job, "internal")
        }
    }

    /** Waits for queued work (tests). */
    internal fun drain() {
        executor.submit {}.get()
    }
}
