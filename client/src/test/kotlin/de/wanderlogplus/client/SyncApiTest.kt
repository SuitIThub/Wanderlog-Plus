package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SyncApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SyncApi =
        SyncApi(HttpClient(MockEngine(handler)), "https://s.example", "tok")

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) = respond(body, status, jsonHeaders)

    private fun error(status: HttpStatusCode, code: String, message: String? = null): SyncApi {
        val msg = if (message != null) ",\"message\":\"$message\"" else ""
        return api { json(status, "{\"error\":\"$code\"$msg}") }
    }

    private suspend fun assertKind(kind: ApiErrorKind, api: SyncApi, code: String? = null, status: Int? = null): ApiException {
        val e = assertFailsWith<ApiException> { api.whoami() }
        assertEquals(kind, e.kind)
        if (code != null) assertEquals(code, e.code)
        if (status != null) assertEquals(status, e.status)
        return e
    }

    @Test fun pushSendsHeaders() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            json(HttpStatusCode.OK, """{"accepted":1,"serverSeq":5,"maxHlc":null,"epoch":"e1"}""")
        }
        val r = api.push(listOf(FieldChange(EntityType.NOTE, "n1", "text", JsonPrimitive("x"), "0000000000001-0000-dev-aaaaaaaaaaaa")))
        assertEquals(1, r.accepted)
        assertEquals("Bearer tok", seen!!.headers[HttpHeaders.Authorization])
        assertEquals("1", seen!!.headers["X-Protocol-Version"])
        assertEquals("application/json", seen!!.body.contentType?.withoutParameters()?.toString())
        assertEquals("/api/sync/push", seen!!.url.encodedPath)
    }

    @Test fun pullQuery() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            json(HttpStatusCode.OK, """{"records":[],"nextSeq":7,"hasMore":false,"epoch":"e1"}""")
        }
        assertEquals(7, api.pull(3, 100).nextSeq)
        assertEquals("3", seen!!.url.parameters["since"])
        assertEquals("100", seen!!.url.parameters["limit"])
    }

    @Test fun healthHasNoAuth() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            json(HttpStatusCode.OK, """{"status":"ok","protocol":1,"epoch":"e","serverTime":123,"extra":1}""")
        }
        assertEquals(1, api.health().protocol)
        assertNull(seen!!.headers[HttpHeaders.Authorization])
    }

    @Test fun whoamiAndImport() = runTest {
        val api = api { req ->
            when (req.url.encodedPath) {
                "/api/whoami" -> json(HttpStatusCode.OK, """{"name":"anna"}""")
                "/api/import/wanderlog" -> json(HttpStatusCode.OK, """{"startedAt":"a","finishedAt":"b","ok":true,"created":2}""")
                else -> json(HttpStatusCode.OK, """[{"startedAt":"a","finishedAt":"b","ok":false,"error":"X"}]""")
            }
        }
        assertEquals("anna", api.whoami())
        assertEquals(2, api.triggerImport().created)
        assertEquals("X", api.importStatus(5).single().error)
    }

    @Test fun errorClassification() = runTest {
        assertKind(ApiErrorKind.UNAUTHORIZED, error(HttpStatusCode.Unauthorized, "unauthorized"), "unauthorized", 401)
        assertKind(ApiErrorKind.PROTOCOL, error(HttpStatusCode.Conflict, "protocol_mismatch"))
        assertKind(ApiErrorKind.CURSOR_AHEAD, error(HttpStatusCode.Conflict, "cursor_ahead"))
        assertKind(ApiErrorKind.CLOCK_DRIFT, error(HttpStatusCode.BadRequest, "clock_drift"))
        assertKind(ApiErrorKind.NOT_CONFIGURED, error(HttpStatusCode.BadRequest, "import_not_configured"))
        val e = assertKind(ApiErrorKind.BAD_REQUEST, error(HttpStatusCode.BadRequest, "invalid_hlc", "change #2: invalid HLC"), "invalid_hlc", 400)
        assertEquals("change #2: invalid HLC", e.message)
        assertKind(ApiErrorKind.SERVER, error(HttpStatusCode.BadGateway, "import_failed"), "import_failed", 502)
        assertKind(ApiErrorKind.SERVER, api { respond("boom", HttpStatusCode.InternalServerError) }, null, 500)
        val n = assertKind(ApiErrorKind.NETWORK, api { throw IOException("down") })
        assertNull(n.status)
    }
}
