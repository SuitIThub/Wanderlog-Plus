package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.AccessToken
import de.wanderlogplus.shared.sync.AccessTokens
import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdminTokensTest {
    private val admin = "admin-secret-0123456789"

    private fun ApplicationTestBuilder.start(adminToken: String? = admin) {
        val dir = Files.createTempDirectory("wlp-admin")
        val base = testServices(dir)
        val config = base.config.copy(adminToken = adminToken)
        application { module(Services(config, base.store, base.sync, base.files, base.imports, base.routes, base.ai, base.transit, TokenService(config, base.store))) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
    }

    @Test fun adminListsCreatesAndRevokesTokens() = testApplication {
        start()
        val list = Json.decodeFromString(AccessTokens.serializer(), client.get("/api/admin/tokens") { bearer(admin) }.bodyAsText())
        assertEquals(listOf("alice" to "env", "bob" to "env"), list.tokens.map { it.name to it.source })
        assertEquals(TOKEN_A, list.tokens.first().token)

        val created = client.post("/api/admin/tokens") { bearer(admin); contentType(ContentType.Application.Json); setBody("""{"name":"Mama"}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        val token = Json.decodeFromString(AccessToken.serializer(), created.bodyAsText())
        assertEquals("app", token.source)
        assertTrue(token.token.length >= 32)

        // The new token syncs right away and is the author of its changes.
        val who = client.get("/api/whoami") { bearer(token.token) }
        assertEquals(HttpStatusCode.OK, who.status)
        assertTrue(who.bodyAsText().contains("Mama"))

        assertEquals(HttpStatusCode.Conflict, client.post("/api/admin/tokens") { bearer(admin); contentType(ContentType.Application.Json); setBody("""{"name":"alice"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/admin/tokens") { bearer(admin); contentType(ContentType.Application.Json); setBody("""{"name":"  "}""") }.status)
        assertEquals(HttpStatusCode.Conflict, client.delete("/api/admin/tokens/alice") { bearer(admin) }.status) // from WLP_TOKENS

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/admin/tokens/Mama") { bearer(admin) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/whoami") { bearer(token.token) }.status) // revoked
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/admin/tokens/Mama") { bearer(admin) }.status)
    }

    @Test fun adminAndSyncTokensAreSeparate() = testApplication {
        start()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/tokens") { bearer(TOKEN_A) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/admin/tokens") { bearer("wrong") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/whoami") { bearer(admin) }.status)
    }

    @Test fun withoutAdminTokenThereIsNoAdministration() = testApplication {
        start(adminToken = null)
        val r = client.get("/api/admin/tokens") { bearer(TOKEN_A) }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("admin_not_configured"))
    }

    @Test fun adminTokenIsCheckedAtStartup() {
        val base = mapOf("WLP_TOKENS" to "alice:$TOKEN_A")
        assertEquals(admin, ServerConfig.fromEnv(base + ("WLP_ADMIN_TOKEN" to admin)).adminToken)
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(base + ("WLP_ADMIN_TOKEN" to "kurz")) }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(base + ("WLP_ADMIN_TOKEN" to TOKEN_A)) }
    }
}
