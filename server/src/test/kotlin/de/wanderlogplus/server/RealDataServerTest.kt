package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.PullResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses the private real export; skipped when absent. Asserts counts only, never prints field values. */
class RealDataServerTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun realExportImportsAndPulls() {
        val file = Path.of(System.getProperty("repoRoot"), "testdata/private/wanderlog-real.json")
        assumeTrue(Files.exists(file), "real export not present")
        val json = Files.readString(file)
        testApplication {
            val services = testServices(dir, source = { json })
            application { module(services) }
            try {
                val c = createClient { install(ContentNegotiation) { json() } }
                val first = c.post("/api/import/wanderlog") { authed() }
                assertEquals(HttpStatusCode.OK, first.status)
                assertEquals(536, first.body<ImportLogEntry>().created)

                val ids = HashSet<String>()
                var since = 0L
                do {
                    val page = c.get("/api/sync/pull?since=$since&limit=100") { authed() }.body<PullResponse>()
                    page.records.forEach { ids += "${it.type}/${it.id}" }
                    since = page.nextSeq
                } while (page.hasMore)
                assertEquals(536, ids.size)

                val second = c.post("/api/import/wanderlog") { authed() }.body<ImportLogEntry>()
                assertTrue(second.ok)
                assertEquals(0, second.created)
                assertEquals(0, second.updated)
            } finally {
                services.store.close()
            }
        }
    }
}
