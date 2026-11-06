package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PushRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
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
        val body = r.bodyAsText()
        assertTrue(body.contains("\"protocol\":1"))
        assertTrue(Regex(""""epoch":"[0-9a-f-]{36}"""").containsMatchIn(body), body)
        assertTrue(Regex(""""serverTime":\d{13}""").containsMatchIn(body), body)
    }

    @Test
    fun unauthorizedBodyIsJson() = app {
        val r = client.get("/api/whoami") { header(PROTOCOL_HEADER, "1") }
        assertEquals("""{"error":"unauthorized"}""", r.bodyAsText())
    }

    @Test
    fun unknownRouteIsJson404() = app {
        val r = client.get("/api/nope") { authed() }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("""{"error":"not_found"}""", r.bodyAsText())
    }

    @Test
    fun pushWithoutContentTypeIs415Json() = app {
        val r = client.post("/api/sync/push") { authed(); setBody("{}") }
        assertEquals(HttpStatusCode.UnsupportedMediaType, r.status)
        assertEquals("""{"error":"unsupported_media_type"}""", r.bodyAsText())
    }

    @Test
    fun foreignIllegalArgumentIs500() {
        testApplication {
            application {
                module(testServices(tmp))
                routing { protectedApi { get("/api/test-iae") { throw IllegalArgumentException("secret") } } }
            }
            val r = client.get("/api/test-iae") { authed() }
            assertEquals(HttpStatusCode.InternalServerError, r.status)
            assertEquals("""{"error":"internal"}""", r.bodyAsText())
        }
    }

    @Test
    fun placesSearchWithoutKeyIs503() = app {
        val r = client.get("/api/places/search?q=Ichiran") { authed() }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertEquals("""{"error":"places_search_not_configured"}""", r.bodyAsText())
    }

    @Test
    fun photoMediaBadIdIs400() = app {
        val r = client.get("/api/photos/media?id=x:1") { authed() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun placesSearchShortQueryIs400() = app {
        val r = client.get("/api/places/search?q=a") { authed() }
        assertEquals(HttpStatusCode.BadRequest, r.status)
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

    @Test
    fun handlerNotRunOnProtocolMismatch() {
        var counter = 0
        testApplication {
            application {
                module(testServices(tmp))
                routing {
                    protectedApi {
                        post("/api/test-sideeffect") { counter++; call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            val bad = client.post("/api/test-sideeffect") {
                header(HttpHeaders.Authorization, "Bearer $TOKEN_A")
                header(PROTOCOL_HEADER, "2")
            }
            assertEquals(HttpStatusCode.Conflict, bad.status)
            assertEquals(0, counter)
            assertEquals(HttpStatusCode.OK, client.post("/api/test-sideeffect") { authed() }.status)
            assertEquals(1, counter)
        }
    }

    @Test
    fun malformedJsonGives400WithoutMessage() {
        testApplication {
            application {
                module(testServices(tmp))
                routing {
                    protectedApi {
                        post("/api/test-body") { call.receive<PushRequest>(); call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            val r = client.post("/api/test-body") {
                authed()
                contentType(ContentType.Application.Json)
                setBody("{not json secret-booking-data")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
            assertEquals("""{"error":"bad_request"}""", r.bodyAsText())
        }
    }

    @Test
    fun runtimeExceptionGives500() {
        testApplication {
            application {
                module(testServices(tmp))
                routing {
                    protectedApi { get("/api/test-boom") { throw RuntimeException("secret-booking-data") } }
                }
            }
            val r = client.get("/api/test-boom") { authed() }
            assertEquals(HttpStatusCode.InternalServerError, r.status)
            assertEquals("""{"error":"internal"}""", r.bodyAsText())
        }
    }
}
