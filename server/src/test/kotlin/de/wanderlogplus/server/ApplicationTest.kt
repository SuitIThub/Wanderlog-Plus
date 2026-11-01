package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    private val tmp = Files.createTempDirectory("wlp-test")

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(testServices(tmp)) }
        block()
    }

    @Test
    fun healthWithoutAuth() = app {
        val r = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"protocol\":1"))
    }

    @Test
    fun missingToken401() = app {
        val r = client.get("/api/whoami") { header(PROTOCOL_HEADER, "1") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun wrongToken401() = app {
        val r = client.get("/api/whoami") { authed("nope-nope-nope-nope-x") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun wrongProtocol409() = app {
        val r = client.get("/api/whoami") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
            header(PROTOCOL_HEADER, "2")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("""{"error":"protocol_mismatch","serverProtocol":1}""", r.bodyAsText())
    }

    @Test
    fun missingProtocol409() = app {
        val r = client.get("/api/whoami") { header(HttpHeaders.Authorization, "Bearer $TOKEN_A") }
        assertEquals(HttpStatusCode.Conflict, r.status)
    }

    @Test
    fun authBeforeProtocol() = app {
        val r = client.get("/api/whoami") { header(PROTOCOL_HEADER, "2") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun whoamiReturnsTokenName() = app {
        val r = client.get("/api/whoami") { authed(TOKEN_B) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("""{"name":"bob"}""", r.bodyAsText())
    }
}
