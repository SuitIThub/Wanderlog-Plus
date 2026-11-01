package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.ErrorResponse
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.PullResponse
import de.wanderlogplus.shared.sync.PushRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportRoutesTest {
    @TempDir
    lateinit var dir: Path

    private fun fixture(): String =
        Files.readString(Path.of(System.getProperty("repoRoot"), "importer/src/test/resources/wanderlog-mini.json"))

    private fun app(source: TripSource?, block: suspend ApplicationTestBuilder.(Services) -> Unit) = testApplication {
        val services = testServices(dir, source = source)
        application { module(services) }
        try {
            block(services)
        } finally {
            services.store.close()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private suspend fun HttpClient.runImport() = post("/api/import/wanderlog") { authed() }

    @Test
    fun importCreatesRecordsAndLogs() = app({ fixture() }) {
        val c = jsonClient()
        val r = c.runImport()
        assertEquals(HttpStatusCode.OK, r.status)
        val entry = r.body<ImportLogEntry>()
        assertTrue(entry.ok)
        assertTrue(entry.created > 0)
        val pull = c.get("/api/sync/pull?since=0&limit=1000") { authed() }.body<PullResponse>()
        assertTrue(pull.records.isNotEmpty())
        val status = c.get("/api/import/status") { authed() }.body<List<ImportLogEntry>>()
        assertEquals(1, status.size)
        assertTrue(status.single().ok)
    }

    @Test
    fun secondImportIsNoop() = app({ fixture() }) { services ->
        val c = jsonClient()
        c.runImport()
        val seq = services.store.maxSeq()
        val second = c.runImport().body<ImportLogEntry>()
        assertTrue(second.ok)
        assertEquals(0, second.created)
        assertEquals(0, second.updated)
        assertEquals(seq, services.store.maxSeq())
    }

    @Test
    fun invalidJsonAbortsBeforeWrite() = app({ """{"foo":1}""" }) { services ->
        val c = jsonClient()
        val r = c.runImport()
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertEquals("import_failed", r.body<ErrorResponse>().error)
        assertEquals(0L, services.store.maxSeq())
        val entry = c.get("/api/import/status") { authed() }.body<List<ImportLogEntry>>().single()
        assertFalse(entry.ok)
        assertFalse(entry.error.isNullOrEmpty())
    }

    @Test
    fun unexpectedFailureLogsOnlyClassName() = app({ throw java.io.IOException("detail with values") }) { services ->
        val c = jsonClient()
        val r = c.runImport()
        assertEquals(HttpStatusCode.BadGateway, r.status)
        val entry = services.store.importLog(10).single()
        assertFalse(entry.ok)
        assertEquals("IOException", entry.error)
        assertFalse(r.body<ErrorResponse>().message.orEmpty().contains("values"))
    }

    @Test
    fun notConfigured400() = app(null) {
        val c = jsonClient()
        val r = c.runImport()
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("import_not_configured", r.body<ErrorResponse>().error)
    }

    @Test
    fun importDoesNotOverwriteOfflineEdit() = app({ fixture() }) { services ->
        val c = jsonClient()
        c.runImport()
        val place = services.store.all(EntityType.PLACE).first()
        val edit = FieldChange(
            EntityType.PLACE, place.id, "notes", JsonPrimitive("device note"),
            hlc(System.currentTimeMillis() + 1000, "dev-a"),
        )
        val push = c.post("/api/sync/push") {
            authed(TOKEN_B); contentType(ContentType.Application.Json); setBody(PushRequest(listOf(edit)))
        }
        assertEquals(HttpStatusCode.OK, push.status)
        val second = c.runImport().body<ImportLogEntry>()
        assertEquals(0, second.conflicts)
        val after = services.store.get(EntityType.PLACE, place.id)
        assertEquals(JsonPrimitive("device note"), after?.fields?.get("notes")?.value)
    }

    @Test
    fun exportContainsAllRecords() = app({ fixture() }) { services ->
        val c = jsonClient()
        c.runImport()
        val r = c.get("/api/export") { authed() }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentDisposition]!!.contains("wanderlog-plus-export.json"))
        val records = r.body<List<EntityRecord>>()
        assertEquals(services.store.all().size, records.size)
        assertTrue(records.isNotEmpty())
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/export").status)
    }

    @Test
    fun statusLimitValidated() = app(null) {
        val c = jsonClient()
        for (q in listOf("0", "101", "x")) {
            assertEquals(HttpStatusCode.BadRequest, c.get("/api/import/status?limit=$q") { authed() }.status, q)
        }
        assertEquals(HttpStatusCode.OK, c.get("/api/import/status?limit=100") { authed() }.status)
    }

    @Test
    fun parseErrorDoesNotLeakInput() {
        val sentinel = "SENTINEL-BOOKING-4711"
        val bad = fixture().replaceFirst("\"id\": 1,", "\"id\": \"$sentinel\",")
        // Precondition: the raw kotlinx message really contains the input.
        val cause = runCatching { de.wanderlogplus.importer.WanderlogParser.parse(bad) }.exceptionOrNull()
        assertTrue(cause?.cause?.message.orEmpty().contains(sentinel), "raw cause must contain sentinel")
        assertFalse(cause?.message.orEmpty().contains(sentinel))
        app({ bad }) { services ->
            val c = jsonClient()
            val r = c.runImport()
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertFalse(r.bodyAsText().contains(sentinel))
            assertFalse(c.get("/api/import/status") { authed() }.bodyAsText().contains(sentinel))
            assertFalse(services.store.importLog(10).single().ok)
        }
    }

    @Test
    fun importFileTakesPrecedenceOverTripKey() {
        val file = dir.resolve("trip.json")
        Files.writeString(file, "from-file")
        val cfg = testConfig(dir).copy(tripKey = "some-key", importFile = file)
        assertEquals("from-file", tripSourceFrom(cfg)!!.fetch())
        assertEquals(null, tripSourceFrom(testConfig(dir)))
    }
}
