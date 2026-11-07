package de.wanderlogplus.app.ui.places

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResearchQueueTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val store = InMemoryEntityStore()
    private val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev-a", now = { 1000L }))
    private val snap = Fixtures.trip()
    private val edit: EditFn = { block -> block(editor, snap) }

    private val kyoto = (1..12).map { "k$it" }
    private val osaka = listOf("o1", "o2", "o3")
    private val releaseFirst = AtomicBoolean(false)
    private val failFirst = AtomicBoolean(false)
    private val posts = Collections.synchronizedList(mutableListOf<String>())
    private val polled = Collections.synchronizedList(mutableListOf<String>())

    private fun job(id: String, status: String, progress: Int, total: Int, n: Int = 0) =
        """{"id":"$id","kind":"research","createdAt":0,"status":"$status","progress":$progress,"total":$total,"appliedOnServer":true,
           "dossiers":[${(1..n).joinToString(",") { """{"savedPlaceId":"x$it","dossier":{"verdict":"v","researchedAt":5}}""" }}]${if (status == "FAILED") ""","error":"research_failed"""" else ""}}"""

    private val api = SyncApi(
        HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/research")) {
                val body = String(req.body.toByteArray())
                val id = if ("k1" in body) "r1" else "r2"
                posts += id
                respond(job(id, "QUEUED", 0, if (id == "r1") 12 else 3), HttpStatusCode.OK, jsonHeaders)
            } else {
                val id = req.url.encodedPath.substringAfterLast('/')
                polled += id
                val answer = when {
                    id == "r1" && !releaseFirst.get() -> job("r1", "RESEARCHING", 3, 12)
                    id == "r1" && failFirst.get() -> job("r1", "FAILED", 3, 12)
                    id == "r1" -> job("r1", "DONE", 12, 12, 12)
                    else -> job("r2", "DONE", 3, 3, 3)
                }
                respond(answer, HttpStatusCode.OK, jsonHeaders)
            }
        }),
        "https://s", "tok",
    )

    private val vm = PlacesViewModel({ MutableStateFlow(snap) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Default), api = { api }, pollMillis = 1)

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("timed out waiting for $what (status: ${vm.researchStatus.value})", cond())
    }

    @Test
    fun statusShowsOnlyRunning() {
        vm.research(kyoto, "Kyoto")
        vm.research(osaka, "Osaka")
        waitFor("progress of Kyoto") { vm.researchStatus.value == "Kyoto: Recherche: 3 von 12 Orten … · danach: Osaka" }
        Thread.sleep(50) // more polls while the first still runs
        assertEquals("Kyoto: Recherche: 3 von 12 Orten … · danach: Osaka", vm.researchStatus.value)
        assertFalse("the waiting job is not polled", "r2" in polled)
        assertEquals(listOf("r1", "r2"), posts.toList().sorted())
        assertEquals("r1", vm.researchJob.value)
        releaseFirst.set(true)
        waitFor("both done") { vm.researching.value.isEmpty() }
        assertEquals("3 Orte recherchiert", vm.researchStatus.value)
        assertEquals("r2", vm.researchJob.value)
    }

    @Test
    fun currentJobFollowsTheQueue() {
        vm.research(kyoto, "Kyoto")
        vm.research(osaka, "Osaka")
        waitFor("Kyoto running") { vm.researchJob.value == "r1" }
        releaseFirst.set(true)
        waitFor("Osaka polled") { "r2" in polled }
        waitFor("done") { vm.researching.value.isEmpty() }
        assertEquals("r2", vm.researchJob.value)
    }

    @Test
    fun noDoubleQueue() {
        vm.research(listOf("a", "b"), "Kyoto")
        vm.research(listOf("b"), "X")
        assertEquals(1, vm.queue.value.size)
        assertEquals(setOf("a", "b"), vm.researching.value)
        waitFor("posted") { posts.isNotEmpty() }
        Thread.sleep(50)
        assertEquals(1, posts.size)
    }

    @Test
    fun failedFirstContinues() {
        failFirst.set(true)
        releaseFirst.set(true)
        vm.research(kyoto, "Kyoto")
        vm.research(osaka, "Osaka")
        waitFor("done") { vm.researching.value.isEmpty() }
        assertTrue("r2" in polled)
        assertEquals("3 Orte recherchiert", vm.researchStatus.value)
    }

    @Test
    fun jobsReachTheServerInQueueOrder() {
        // The first start is slow (e.g. reading the config): the next ones must still reach the server after it,
        // or the server's lane order differs from the app's and the app follows a job that only waits.
        val order = Collections.synchronizedList(mutableListOf<String>())
        val slowApi = SyncApi(
            HttpClient(MockEngine { req ->
                val body = String(req.body.toByteArray())
                val id = Regex(""""(r\d)"""").find(body)?.groupValues?.get(1) ?: "?"
                if (id == "r1") Thread.sleep(300)
                order += id
                respond(job(id, "DONE", 1, 1, 1), HttpStatusCode.OK, jsonHeaders)
            }),
            "https://s", "tok",
        )
        val v = PlacesViewModel({ MutableStateFlow(snap) }, edit, CoroutineScope(SupervisorJob() + Dispatchers.Default), api = { slowApi }, pollMillis = 1)
        v.researchRegions(listOf("A" to listOf("r1"), "B" to listOf("r2"), "C" to listOf("r3"), "D" to listOf("r4")))
        val deadline = System.currentTimeMillis() + 5_000
        while (order.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(listOf("r1", "r2", "r3", "r4"), order.toList())
    }

    @Test
    fun queueStatusTexts() {
        val running = QueuedResearch(1, "Kyoto", kyoto, "r1", 3, 12, QueuedResearch.State.RUNNING)
        val waiting = QueuedResearch(2, "Osaka", osaka, null, 0, 3, QueuedResearch.State.WAITING)
        val nara = QueuedResearch(3, "Nara", listOf("n1"), null, 0, 1, QueuedResearch.State.WAITING)
        assertEquals("Kyoto: Recherche: 3 von 12 Orten … · danach: Osaka, Nara", queueStatus(listOf(running, waiting, nara)))
        assertEquals("Ort wird recherchiert …", queueStatus(listOf(QueuedResearch(4, "", listOf("s"), "r", 0, 1, QueuedResearch.State.RUNNING))))
        assertEquals("2 Orte recherchiert", queueStatus(listOf(running.copy(state = QueuedResearch.State.DONE, outcome = "2 Orte recherchiert"))))
        assertEquals(null, queueStatus(emptyList()))
    }
}
