package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.PullResponse
import de.wanderlogplus.shared.sync.PushRequest
import de.wanderlogplus.shared.sync.PushResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncRoutesTest {
    @TempDir
    lateinit var dir: Path

    private fun app(block: suspend ApplicationTestBuilder.(Services) -> Unit) = testApplication {
        val services = testServices(dir)
        application { module(services) }
        try {
            block(services)
        } finally {
            services.store.close()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private val change = FieldChange(EntityType.PLACE, "p1", "title", JsonPrimitive("Cafe"), hlc(1_000L, "dev-b"))

    @Test
    fun pushThenPull() = app { services ->
        val c = jsonClient()
        val r = c.post("/api/sync/push") {
            authed(TOKEN_B); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(change)))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(1, r.body<PushResponse>().accepted)
        val pull = c.get("/api/sync/pull?since=0") { authed(TOKEN_A) }
        assertEquals(HttpStatusCode.OK, pull.status)
        val body = pull.body<PullResponse>()
        assertEquals("p1", body.records.single().id)
        assertEquals("bob", services.store.authorOf(EntityType.PLACE, "p1", "title"))
    }

    @Test
    fun invalidQueryIs400() = app {
        val c = jsonClient()
        for (q in listOf("since=-1", "limit=0", "limit=1001", "since=abc", "limit=x")) {
            assertEquals(HttpStatusCode.BadRequest, c.get("/api/sync/pull?$q") { authed() }.status, q)
        }
        assertEquals(HttpStatusCode.OK, c.get("/api/sync/pull") { authed() }.status)
    }

    @Test
    fun cursorAheadIs409AndAtMaxIsEmpty() = app { services ->
        val c = jsonClient()
        c.post("/api/sync/push") { authed(); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(change))) }
        val max = services.store.maxSeq()
        val ahead = c.get("/api/sync/pull?since=${max + 1}") { authed() }
        assertEquals(HttpStatusCode.Conflict, ahead.status)
        assertEquals("""{"error":"cursor_ahead"}""", ahead.bodyAsText())
        val at = c.get("/api/sync/pull?since=$max") { authed() }
        assertEquals(HttpStatusCode.OK, at.status)
        val body = at.body<PullResponse>()
        assertTrue(body.records.isEmpty())
        assertEquals(services.store.epoch, body.epoch)
    }

    @Test
    fun pushResponseCarriesEpoch() = app { services ->
        val r = jsonClient().post("/api/sync/push") {
            authed(); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(change)))
        }.body<PushResponse>()
        assertEquals(services.store.epoch, r.epoch)
    }

    @Test
    fun unknownTypeIs400() = app {
        val c = jsonClient()
        val r = c.post("/api/sync/push") {
            authed(); contentType(ContentType.Application.Json)
            setBody("""{"changes":[{"type":"BOGUS","id":"x","field":"f","value":1,"hlc":"000000000001000-000000-dev"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun rejectedPushIs400WithCode() = app {
        val c = jsonClient()
        val r = c.post("/api/sync/push") {
            authed(); contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(change.copy(hlc = "kaputt"))))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("\"invalid_hlc\""))
    }

    @Test
    fun syncRequiresAuth() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/sync/pull").status)
    }
}
