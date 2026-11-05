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
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun vaultUploadTriggerGetAndClear() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val api = api { req ->
            seen += req
            if (req.method == io.ktor.http.HttpMethod.Get) {
                json(HttpStatusCode.OK, """{"requested":true}""")
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        assertEquals(true, api.vaultUploadRequested())
        api.clearVaultUploadRequest()
        assertEquals("/api/vault/uploads/request", seen[0].url.encodedPath)
        assertEquals("/api/vault/uploads/request/clear", seen[1].url.encodedPath)
        assertEquals(listOf(io.ktor.http.HttpMethod.Get, io.ktor.http.HttpMethod.Post), seen.map { it.method })
        assertEquals("Bearer tok", seen[1].headers[HttpHeaders.Authorization])
    }

    @Test fun vaultImageByHashReturnsImage() = runTest {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        var path = ""
        var method: io.ktor.http.HttpMethod? = null
        val api = api { req ->
            path = req.url.encodedPath
            method = req.method
            json(HttpStatusCode.OK, """{"id":"i1","fileName":"a.jpg","mimeType":"image/jpeg","size":3,"score":0.5,"uploadedAt":7,"hash":"$hash"}""")
        }
        val info = api.vaultImageByHash(hash)
        assertEquals("/api/vault/images/by-hash/$hash", path)
        assertEquals(io.ktor.http.HttpMethod.Get, method)
        assertEquals("i1", info?.id)
        assertEquals(hash, info?.hash)
    }

    @Test fun vaultImageByHashIsNullOn404() = runTest {
        val api = api { json(HttpStatusCode.NotFound, """{"error":"not_found"}""") }
        assertNull(api.vaultImageByHash("0".repeat(64)))
    }

    @Test fun vaultImageByHashRethrowsNon404() = runTest {
        val api = api { json(HttpStatusCode.InternalServerError, """{"error":"boom"}""") }
        assertEquals(ApiErrorKind.SERVER, assertFailsWith<ApiException> { api.vaultImageByHash("0".repeat(64)) }.kind)
    }

    @Test fun vaultUploadTriggerErrorPropagates() = runTest {
        val api = error(HttpStatusCode.InternalServerError, "boom")
        assertFailsWith<ApiException> { api.vaultUploadRequested() }
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

    @Test fun pullDropsUnknownEntityTypes() = runTest {
        val api = api {
            json(
                HttpStatusCode.OK,
                """{"records":[
                  {"type":"HOLOGRAM","id":"x","fields":{"a":{"value":"v","hlc":"0000000000001-0000-dev-aaaaaaaaaaaa"}}},
                  {"type":"NOTE","id":"n1","fields":{"text":{"value":"v","hlc":"0000000000001-0000-dev-aaaaaaaaaaaa"}}}
                ],"nextSeq":7,"hasMore":false,"epoch":"e1"}""",
            )
        }
        val r = api.pull(0)
        assertEquals(listOf("n1"), r.records.map { it.id })
        assertEquals(7, r.nextSeq)
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

    @Test fun vaultUploadTriggerParsesFullShape() = runTest {
        val api = api { json(HttpStatusCode.OK, """{"requested":false,"paused":true,"videoRefs":["r1","r2"]}""") }
        val t = api.vaultUploadTrigger()
        assertEquals(false, t.requested)
        assertEquals(true, t.paused)
        assertEquals(listOf("r1", "r2"), t.videoRefs)
    }

    @Test fun vaultUploadTriggerParsesLegacyShape() = runTest {
        val api = api { json(HttpStatusCode.OK, """{"requested":true}""") }
        val t = api.vaultUploadTrigger()
        assertEquals(true, t.requested)
        assertEquals(false, t.paused)
        assertEquals(emptyList(), t.videoRefs)
    }

    @Test fun startVaultVideoSessionPostsAllFields() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            json(HttpStatusCode.OK, """{"id":"s1","videoRef":"r","fileName":"v.mp4","size":10,"sha256":"ab","partSize":8388608,"receivedParts":[],"complete":false}""")
        }
        val s = api.startVaultVideoSession("r", "v.mp4", 10, "ab", "video/mp4")
        assertEquals("s1", s.id)
        assertEquals("/api/vault/videos/sessions", seen!!.url.encodedPath)
        assertEquals(io.ktor.http.HttpMethod.Post, seen!!.method)
        val body = String(seen!!.body.toByteArray())
        assertEquals("""{"videoRef":"r","fileName":"v.mp4","size":10,"sha256":"ab","videoMimeType":"video/mp4"}""", body)
    }

    @Test fun vaultVideoPartSendsRawBytesAndHash() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            json(HttpStatusCode.OK, """{"id":"s1","videoRef":"r","fileName":"v.mp4","size":10,"sha256":"ab","partSize":8388608,"receivedParts":[2],"complete":false}""")
        }
        api.uploadVaultVideoPart("s1", 2, byteArrayOf(1, 2, 3), "c".repeat(64))
        assertEquals("/api/vault/videos/sessions/s1/parts/2", seen!!.url.encodedPath)
        assertEquals(io.ktor.http.HttpMethod.Put, seen!!.method)
        assertEquals("c".repeat(64), seen!!.headers["X-Part-Sha256"])
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), seen!!.body.toByteArray().toList())
    }

    @Test fun vaultVideoPartMapsConflictAndGone() = runTest {
        val conflict = api { json(HttpStatusCode.Conflict, """{"error":"part_conflict"}""") }
        val e1 = assertFailsWith<ApiException> { conflict.uploadVaultVideoPart("s1", 0, byteArrayOf(1), "c".repeat(64)) }
        assertEquals(ApiErrorKind.BAD_REQUEST, e1.kind)
        assertEquals("part_conflict", e1.code)
        val gone = api { json(HttpStatusCode.NotFound, """{"error":"not_found"}""") }
        val e2 = assertFailsWith<ApiException> { gone.uploadVaultVideoPart("s1", 0, byteArrayOf(1), "c".repeat(64)) }
        assertEquals("not_found", e2.code)
    }

    @Test fun vaultVideoSessionIsNullOn404() = runTest {
        val api = api { json(HttpStatusCode.NotFound, """{"error":"not_found"}""") }
        assertNull(api.vaultVideoSession("s1"))
    }

    @Test fun completeVaultVideoSessionReturnsEntry() = runTest {
        var path = ""
        val api = api { req ->
            path = req.url.encodedPath
            json(HttpStatusCode.OK, """{"id":"e1","fileName":"clip.jpg","mimeType":"image/jpeg","size":3,"score":0.0,"uploadedAt":7,"kind":"VIDEO","hasVideo":true,"videoHash":"h","videoMimeType":"video/mp4"}""")
        }
        val info = api.completeVaultVideoSession("s1")
        assertEquals("/api/vault/videos/sessions/s1/complete", path)
        assertEquals(true, info.hasVideo)
        assertEquals(de.wanderlogplus.shared.sync.VaultKind.VIDEO, info.kind)
    }

    @Test fun vaultVideoRequestAndPauseUseRawPosts() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val api = api { req ->
            seen += req
            respond("", HttpStatusCode.NoContent)
        }
        api.requestVaultVideo("e1")
        api.unrequestVaultVideo("e1")
        api.setVaultUploadsPaused(true)
        api.setVaultUploadsPaused(false)
        assertEquals(
            listOf(
                "/api/vault/videos/e1/request",
                "/api/vault/videos/e1/unrequest",
                "/api/vault/uploads/pause",
                "/api/vault/uploads/resume",
            ),
            seen.map { it.url.encodedPath },
        )
        assertTrue(seen.all { it.method == io.ktor.http.HttpMethod.Post })
    }

    @Test fun deleteVaultImagesPostsIdsAndReturnsCount() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            json(HttpStatusCode.OK, """{"deleted":2}""")
        }
        assertEquals(2, api.deleteVaultImages(listOf("a", "b")))
        assertEquals("/api/vault/images/bulk-delete", seen!!.url.encodedPath)
        assertEquals("""{"ids":["a","b"]}""", String(seen!!.body.toByteArray()))
    }

    @Test fun uploadVaultImageCarriesVideoFieldsOnlyWhenSet() = runTest {
        val bodies = mutableListOf<String>()
        val api = api { req ->
            bodies += String(req.body.toByteArray())
            json(HttpStatusCode.OK, """{"id":"e1","fileName":"x.jpg","mimeType":"image/jpeg","size":3,"score":0.0,"uploadedAt":7}""")
        }
        val file = java.io.File.createTempFile("vault", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            api.uploadVaultImage(file, "x.jpg", "image/jpeg", 0.0)
            api.uploadVaultImage(
                file, "x.jpg", "image/jpeg", 0.0,
                kind = de.wanderlogplus.shared.sync.VaultKind.VIDEO,
                videoRef = "content://m/1", durationMs = 6500, videoBytes = 123456,
            )
        } finally {
            file.delete()
        }
        // Photos stay exactly as before: none of the video fields appear.
        assertTrue(!bodies[0].contains("videoRef") && !bodies[0].contains("durationMs") && !bodies[0].contains("name=kind"), bodies[0])
        assertTrue(bodies[1].contains("name=kind\r\n") && bodies[1].contains("\r\n\r\nvideo\r\n"), bodies[1])
        assertTrue(bodies[1].contains("name=videoRef\r\n") && bodies[1].contains("\r\n\r\ncontent://m/1\r\n"), bodies[1])
        assertTrue(bodies[1].contains("name=durationMs\r\n") && bodies[1].contains("\r\n\r\n6500\r\n"), bodies[1])
        assertTrue(bodies[1].contains("name=videoBytes\r\n") && bodies[1].contains("\r\n\r\n123456\r\n"), bodies[1])
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
