package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val node = "dev-aaaaaaaaaaaa"

    private fun hlc(wall: Long, counter: Int = 0, n: String = node) = Hlc(wall, counter, n).encode()
    private fun change(id: String, field: String = "text", wall: Long = 1000, n: String = node) =
        FieldChange(EntityType.NOTE, id, field, JsonPrimitive("v-$id-$field"), hlc(wall, 0, n))

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(body, status, jsonHeaders)
    private fun MockRequestHandleScope.health(epoch: String = "e1") =
        json(HttpStatusCode.OK, """{"status":"ok","protocol":1,"epoch":"$epoch","serverTime":0}""")
    private fun MockRequestHandleScope.err(status: HttpStatusCode, code: String, msg: String? = null) =
        json(status, if (msg == null) """{"error":"$code"}""" else """{"error":"$code","message":"$msg"}""")
    private fun MockRequestHandleScope.emptyPull(epoch: String = "e1", next: Long = 0) =
        json(HttpStatusCode.OK, """{"records":[],"nextSeq":$next,"hasMore":false,"epoch":"$epoch"}""")

    private inner class Rig(
        val clockNow: Long = 5_000,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val store = InMemoryEntityStore()
        val outbox = InMemoryOutbox()
        val state = InMemorySyncState()
        val clock = HlcClock(node, now = { clockNow })
        val requests = mutableListOf<HttpRequestData>()
        val api = SyncApi(
            HttpClient(MockEngine { req -> requests += req; handler(req) }),
            "https://s.example", "tok",
        )
        val engine = SyncEngine(api, store, outbox, state, clock, now = { 42L })
        fun paths() = requests.map { it.url.encodedPath }
    }

    private fun pushOk(maxHlc: String? = null) =
        """{"accepted":1,"serverSeq":1,"maxHlc":${maxHlc?.let { "\"$it\"" }},"epoch":"e1"}"""

    @Test fun pushClearsOutboxOnlyAfter200() = runTest {
        var outboxSizeDuringPush = -1
        lateinit var rig: Rig
        rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> { outboxSizeDuringPush = rig.outbox.size(); json(HttpStatusCode.OK, pushOk()) }
                else -> emptyPull()
            }
        }
        rig.outbox.add(listOf(change("a"), change("b")))
        val r = rig.engine.sync()
        assertEquals(SyncResult.Ok(2, 0), r)
        assertEquals(2, outboxSizeDuringPush)
        assertEquals(0, rig.outbox.size())
        assertEquals(42L, rig.state.lastSyncAt)
        assertNull(rig.state.lastError)
    }

    @Test fun serverErrorKeepsOutboxIntact() = runTest {
        val rig = Rig { req ->
            if (req.url.encodedPath == "/api/health") health() else err(HttpStatusCode.BadGateway, "bad_gateway")
        }
        rig.outbox.add(listOf(change("a"), change("b"), change("c")))
        val r = rig.engine.sync()
        assertEquals(SyncResult.Failed(ApiErrorKind.SERVER, "Serverfehler"), r)
        assertEquals(3, rig.outbox.size())
        assertEquals("Serverfehler", rig.state.lastError)
    }

    @Test fun networkErrorKeepsOutboxIntact() = runTest {
        val rig = Rig { req ->
            if (req.url.encodedPath == "/api/health") health() else throw IOException("down")
        }
        rig.outbox.add(listOf(change("a")))
        val r = assertIs<SyncResult.Failed>(rig.engine.sync())
        assertEquals(ApiErrorKind.NETWORK, r.kind)
        assertEquals("Server nicht erreichbar", r.message)
        assertEquals(1, rig.outbox.size())
    }

    @Test fun indexedRejectRemovesOnlyThatEntryAndResendsRest() = runTest {
        val sentSizes = mutableListOf<Int>()
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> {
                    val body = (req.body as TextContent).text
                    val n = Regex("\"hlc\"").findAll(body).count()
                    sentSizes += n
                    if (n == 3) err(HttpStatusCode.BadRequest, "invalid_hlc", "change #2: invalid HLC")
                    else json(HttpStatusCode.OK, pushOk())
                }
                else -> emptyPull()
            }
        }
        rig.outbox.add(listOf(change("a"), change("b"), change("c")))
        val r = rig.engine.sync()
        assertEquals(SyncResult.Ok(2, 0, rejected = 1), r)
        assertEquals(listOf(3, 2), sentSizes)
        assertEquals(0, rig.outbox.size())
        assertEquals("1 Änderung(en) vom Server abgelehnt", rig.state.lastError)
        assertEquals(42L, rig.state.lastSyncAt)
    }

    @Test fun clockDriftMessageShowsServerAndDeviceTime() = runTest {
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> json(HttpStatusCode.OK, """{"status":"ok","protocol":1,"epoch":"e1","serverTime":1798761600000}""")
                else -> err(HttpStatusCode.BadRequest, "clock_drift")
            }
        }
        rig.outbox.add(listOf(change("a")))
        val engine = SyncEngine(rig.api, rig.store, rig.outbox, rig.state, rig.clock, now = { 1798761600000L + 3 * 3600_000L }, zone = java.time.ZoneOffset.UTC)
        val r = assertIs<SyncResult.Failed>(engine.sync())
        assertEquals(ApiErrorKind.CLOCK_DRIFT, r.kind)
        assertEquals("Uhrzeit des Geräts weicht stark ab (Server: 01.01. 00:00, Gerät: 01.01. 03:00)", r.message)
        assertEquals(r.message, rig.state.lastError)
    }

    @Test fun unindexedBadRequestFailsAndKeepsOutbox() = runTest {
        val rig = Rig { req ->
            if (req.url.encodedPath == "/api/health") health() else err(HttpStatusCode.BadRequest, "invalid_change", "bad")
        }
        rig.outbox.add(listOf(change("a")))
        val r = assertIs<SyncResult.Failed>(rig.engine.sync())
        assertEquals(ApiErrorKind.BAD_REQUEST, r.kind)
        assertEquals("Server hat Änderungen abgelehnt", r.message)
        assertEquals(1, rig.outbox.size())
    }

    @Test fun pullsThreePagesAndSetsSinceToLastNextSeq() = runTest {
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/pull" -> {
                    val since = req.url.parameters["since"]!!.toLong()
                    assertEquals("500", req.url.parameters["limit"])
                    val (id, next, more) = when (since) {
                        0L -> Triple("p1", 10L, true)
                        10L -> Triple("p2", 20L, true)
                        else -> Triple("p3", 30L, false)
                    }
                    json(
                        HttpStatusCode.OK,
                        """{"records":[{"type":"NOTE","id":"$id","fields":{"text":{"value":"x","hlc":"${hlc(1000)}"}}}],"nextSeq":$next,"hasMore":$more,"epoch":"e1"}""",
                    )
                }
                else -> error("unexpected")
            }
        }
        val r = rig.engine.sync()
        assertEquals(SyncResult.Ok(0, 3), r)
        assertEquals(30L, rig.state.since)
        assertEquals(3, rig.store.all().size)
        assertEquals(3, rig.paths().count { it == "/api/sync/pull" })
    }

    @Test fun maxHlcFromPushAdvancesClock() = runTest {
        val remote = Hlc(5_500, 7, "server")
        val rig = Rig(clockNow = 5_000) { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> json(HttpStatusCode.OK, pushOk(remote.encode()))
                else -> emptyPull()
            }
        }
        rig.outbox.add(listOf(change("a")))
        rig.engine.sync()
        assertTrue(rig.clock.tick() > remote)
    }

    @Test fun pulledHlcsAdvanceClock() = runTest {
        val remote = Hlc(5_900, 0, "dev-bbbbbbbbbbbb")
        val rig = Rig(clockNow = 5_000) { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                else -> json(
                    HttpStatusCode.OK,
                    """{"records":[{"type":"NOTE","id":"n","fields":{"text":{"value":"x","hlc":"${remote.encode()}"}}}],"nextSeq":1,"hasMore":false,"epoch":"e1"}""",
                )
            }
        }
        rig.engine.sync()
        assertTrue(rig.clock.tick() > remote)
    }

    private fun seedRecord(rig: Rig) {
        val rec = EntityRecord(
            EntityType.SAVED_PLACE, "sp1",
            mapOf(
                "note" to FieldValue(JsonPrimitive("n"), hlc(2000)),
                "deleted" to FieldValue(JsonPrimitive(false), hlc(2000, 1)),
                "status" to FieldValue(JsonPrimitive("IDEA"), hlc(100, 0, "importer")),
                "_conflict.note" to FieldValue(JsonPrimitive("w"), hlc(100, 1, "importer")),
                "_removedInSource" to FieldValue(JsonPrimitive(false), hlc(100, 2, "server")),
                "_source" to FieldValue(JsonPrimitive("wanderlog"), hlc(100, 3, "importer")),
                "_sourceId" to FieldValue(JsonPrimitive("x"), hlc(2000, 2)),
                "_base.note" to FieldValue(JsonPrimitive("b"), hlc(2000, 3)),
            ),
        )
        rig.store.put(rec)
    }

    @Test fun epochChangeResetsAndRepushesDeviceFieldsOnly() = runTest {
        val failing = Rig { req ->
            if (req.url.encodedPath == "/api/health") health("e2") else err(HttpStatusCode.BadGateway, "x")
        }
        seedRecord(failing)
        failing.state.epoch = "e1"
        failing.state.since = 99
        failing.outbox.add(listOf(change("stale")))
        failing.engine.sync()
        assertEquals("e2", failing.state.epoch)
        assertEquals(0L, failing.state.since)
        val queued = failing.outbox.peek(100).map { it.change.field }.sorted()
        assertEquals(listOf("_removedInSource", "deleted", "note"), queued)
        assertTrue(failing.outbox.peek(100).all { Hlc.parse(it.change.hlc).nodeId == node })

        val ok = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health("e2")
                "/api/sync/push" -> json(HttpStatusCode.OK, pushOk())
                else -> emptyPull("e2")
            }
        }
        seedRecord(ok)
        ok.state.epoch = "e1"
        ok.state.since = 99
        assertEquals(SyncResult.Ok(3, 0), ok.engine.sync())
        assertEquals(0, ok.outbox.size())
    }

    @Test fun firstSyncStoresEpochWithoutReset() = runTest {
        val rig = Rig { req -> if (req.url.encodedPath == "/api/health") health("e9") else emptyPull("e9", 5) }
        seedRecord(rig)
        rig.engine.sync()
        assertEquals("e9", rig.state.epoch)
        assertEquals(0, rig.outbox.size())
    }

    @Test fun cursorAheadOnceResetsAndSucceeds() = runTest {
        var pulls = 0
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> json(HttpStatusCode.OK, pushOk())
                else -> if (pulls++ == 0) err(HttpStatusCode.Conflict, "cursor_ahead") else emptyPull("e1", 3)
            }
        }
        seedRecord(rig)
        rig.state.epoch = "e1"
        rig.state.since = 500
        val r = rig.engine.sync()
        assertEquals(SyncResult.Ok(3, 0), r)
        assertEquals(3L, rig.state.since)
        assertEquals("e1", rig.state.epoch)
        assertEquals(2, rig.paths().count { it == "/api/sync/pull" })
    }

    @Test fun cursorAheadTwiceFails() = runTest {
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> json(HttpStatusCode.OK, pushOk())
                else -> err(HttpStatusCode.Conflict, "cursor_ahead")
            }
        }
        rig.state.epoch = "e1"
        val r = assertIs<SyncResult.Failed>(rig.engine.sync())
        assertEquals(ApiErrorKind.CURSOR_AHEAD, r.kind)
        assertEquals("Sync-Zustand passt nicht zum Server", r.message)
        assertEquals(2, rig.paths().count { it == "/api/sync/pull" })
        assertNotNull(rig.state.lastError)
    }

    @Test fun unauthorizedFails() = runTest {
        val rig = Rig { req ->
            if (req.url.encodedPath == "/api/health") health() else err(HttpStatusCode.Unauthorized, "unauthorized")
        }
        val r = assertIs<SyncResult.Failed>(rig.engine.sync())
        assertEquals(ApiErrorKind.UNAUTHORIZED, r.kind)
        assertEquals("Token ungültig", r.message)
        assertEquals("Token ungültig", rig.state.lastError)
        assertNull(rig.state.lastSyncAt)
    }

    @Test fun resetLocalCursorIsAppliedBySyncAndKeepsEpoch() = runTest {
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> json(HttpStatusCode.OK, pushOk())
                else -> emptyPull("e1", 4)
            }
        }
        seedRecord(rig)
        rig.state.epoch = "e1"
        rig.state.since = 77
        rig.outbox.add(listOf(change("dup")))
        rig.engine.resetLocalCursor()
        assertTrue(rig.state.pendingReset)
        assertEquals(1, rig.outbox.size())
        assertEquals(SyncResult.Ok(3, 0), rig.engine.sync())
        assertEquals(false, rig.state.pendingReset)
        assertEquals("e1", rig.state.epoch)
        assertEquals(4L, rig.state.since)
        assertEquals(0, rig.outbox.size())
        assertEquals(
            listOf(0L),
            rig.requests.filter { it.url.encodedPath == "/api/sync/pull" }.map { it.url.parameters["since"]!!.toLong() },
        )
    }

    @Test fun unexpectedExceptionBecomesFailedAndKeepsOutbox() = runTest {
        val rig = Rig { req ->
            when (req.url.encodedPath) {
                "/api/health" -> health()
                "/api/sync/push" -> json(HttpStatusCode.OK, pushOk())
                else -> json(
                    HttpStatusCode.OK,
                    """{"records":[{"type":"NOTE","id":"n","fields":{"text":{"value":"x","hlc":"garbage"}}}],"nextSeq":1,"hasMore":false,"epoch":"e1"}""",
                )
            }
        }
        rig.outbox.add(listOf(change("a")))
        val r = assertIs<SyncResult.Failed>(rig.engine.sync())
        assertEquals(ApiErrorKind.SERVER, r.kind)
        assertEquals("Unerwarteter Fehler beim Synchronisieren", r.message)
        assertEquals(r.message, rig.state.lastError)
        assertEquals(0L, rig.state.since)
    }
}
