package de.wanderlogplus.server.ai

import de.wanderlogplus.server.SqliteStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchLaneTest {
    private val tmp = Files.createTempDirectory("wlp-lane")
    private val store = SqliteStore(tmp.resolve("t.db"))

    /** Keeps the submitted tasks instead of running them. */
    private class Recording : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val noMedia = object : ReelMedia {
        override fun download(url: String, dir: Path): Downloaded = error("unused")
        override fun extract(video: Path, dir: Path): Media = error("unused")
    }

    private val pool = Recording()
    private val lane = Recording()
    private val jobs = AiJobs(store, null, noMedia, tmp.resolve("work"), executor = pool, researchExecutor = lane)

    @Test
    fun researchGoesToItsOwnLane() {
        jobs.startResearch(listOf("s1"))
        jobs.startResearch(listOf("s2"))
        jobs.startReel(ReelInput.Link("https://www.instagram.com/reel/a/"))
        assertEquals(2, lane.tasks.size)
        assertEquals(1, pool.tasks.size)
    }

    @Test
    fun secondResearchSaysItWaits() {
        val a = jobs.startResearch(listOf("s1"))
        val b = jobs.startResearch(listOf("s2"))
        assertTrue(a.steps.single().text.contains("wartet auf einen freien Platz"), a.steps.single().text)
        assertEquals("Wartet auf die laufende Recherche …", b.steps.single().text)
        // After both finished, a new one does not wait.
        lane.tasks.forEach { it.run() }
        val c = jobs.startResearch(listOf("s3"))
        assertTrue(c.steps.single().text.contains("wartet auf einen freien Platz"), c.steps.single().text)
    }

    @Test
    fun defaultLaneRunsOneAtATime() {
        val j = AiJobs(store, null, noMedia, tmp.resolve("work2"))
        assertEquals(1, j.researchLaneSize)
    }
}
