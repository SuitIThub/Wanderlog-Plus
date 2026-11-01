package de.wanderlogplus.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun api(base: String = "https://s.example", handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SyncApi(HttpClient(MockEngine(handler)), base, "tok")

    private fun tmp(): File = createTempDirectory("fileapi").toFile().also { it.deleteOnExit() }

    @Test fun uploadSendsMultipartWithAuthHeaders() = runTest {
        var seen: HttpRequestData? = null
        var body = ""
        val api = api { req ->
            seen = req
            body = String(req.body.toByteArray(), Charsets.ISO_8859_1)
            respond("""{"hash":"abcd","size":3,"duplicate":false}""", HttpStatusCode.OK, jsonHeaders)
        }
        val f = File(tmp(), "x.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val r = api.uploadFile(f, "Ticket.pdf", "application/pdf")
        assertEquals(UploadedFile("abcd", 3, false), r)
        val req = seen!!
        assertEquals("/api/files", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers[HttpHeaders.Authorization])
        assertEquals("1", req.headers["X-Protocol-Version"])
        assertTrue(req.body.contentType.toString().startsWith("multipart/form-data"))
        assertTrue(body.contains("name=file"), body)
        assertTrue(body.contains("filename=Ticket.pdf"), body)
        assertTrue(body.contains("application/pdf"), body)
    }

    @Test fun uploadTooLargeMapsTo413Code() = runTest {
        val api = api { respond("""{"error":"file_too_large"}""", HttpStatusCode.PayloadTooLarge, jsonHeaders) }
        val f = File(tmp(), "x.bin").also { it.writeBytes(byteArrayOf(1)) }
        val e = assertFailsWith<ApiException> { api.uploadFile(f, "a.bin", "application/octet-stream") }
        assertEquals(ApiErrorKind.BAD_REQUEST, e.kind)
        assertEquals("file_too_large", e.code)
        assertEquals(413, e.status)
    }

    @Test fun downloadWritesFileWhenHashMatches() = runTest {
        val api = api { respond("abc".toByteArray(), HttpStatusCode.OK) }
        val dest = File(tmp(), "out.bin")
        api.downloadFile(abcHash, dest)
        assertEquals("abc", dest.readText())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun downloadRejectsHashMismatch() = runTest {
        val api = api { respond("abd".toByteArray(), HttpStatusCode.OK) }
        val dest = File(tmp(), "out.bin")
        val e = assertFailsWith<ApiException> { api.downloadFile(abcHash, dest) }
        assertEquals(ApiErrorKind.SERVER, e.kind)
        assertEquals("Datei beschädigt", e.message)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun download404IsNotFound() = runTest {
        val api = api { respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, jsonHeaders) }
        val dest = File(tmp(), "out.bin")
        val e = assertFailsWith<ApiException> { api.downloadFile(abcHash, dest) }
        assertEquals(ApiErrorKind.BAD_REQUEST, e.kind)
        assertEquals("not_found", e.code)
        assertEquals(404, e.status)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun downloadSendsAuthHeaders() = runTest {
        var seen: HttpRequestData? = null
        val api = api { req ->
            seen = req
            respond("abc".toByteArray(), HttpStatusCode.OK)
        }
        api.downloadFile(abcHash, File(tmp(), "out.bin"))
        assertEquals("/api/files/$abcHash", seen!!.url.encodedPath)
        assertEquals("Bearer tok", seen!!.headers[HttpHeaders.Authorization])
        assertEquals("1", seen!!.headers["X-Protocol-Version"])
    }

    @Test fun prefixIsUsedForRequests() = runTest {
        var seen: HttpRequestData? = null
        val api = api("https://h/wlp") { req ->
            seen = req
            respond("""{"status":"ok","protocol":1,"epoch":"e","serverTime":1}""", HttpStatusCode.OK, jsonHeaders)
        }
        api.health()
        assertEquals("https://h/wlp/api/health", seen!!.url.toString())
    }
}
