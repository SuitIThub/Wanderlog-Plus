package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.PROTOCOL_HEADER
import de.wanderlogplus.shared.sync.PROTOCOL_VERSION
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRoutesTest {
    @TempDir
    lateinit var dir: Path

    private fun filesDir(): Path = dir.resolve("files")

    private fun app(maxBytes: Long = 25L * 1024 * 1024, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val services = testServices(dir, maxBytes)
        application { module(services) }
        try {
            block()
        } finally {
            services.store.close()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private suspend fun io.ktor.client.HttpClient.upload(bytes: ByteArray, partName: String = "file"): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/files",
            formData = formData {
                append(partName, bytes, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"x.bin\"")
                })
            },
        ) { authed() }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun fileCount(): Int = if (Files.exists(filesDir())) Files.list(filesDir()).use { it.count().toInt() } else 0

    @Test
    fun uploadAndDownload() = app {
        val c = jsonClient()
        val bytes = Random(1).nextBytes(3 * 1024)
        val r = c.upload(bytes)
        assertEquals(HttpStatusCode.OK, r.status)
        val stored: StoredFile = r.body()
        assertEquals(sha256(bytes), stored.hash)
        assertEquals(bytes.size.toLong(), stored.size)
        assertEquals(false, stored.duplicate)
        val d = c.get("/api/files/${stored.hash}") { authed() }
        assertEquals(HttpStatusCode.OK, d.status)
        assertEquals(ContentType.Application.OctetStream, d.contentType()?.withoutParameters())
        assertContentEquals(bytes, d.bodyAsBytes())
    }

    @Test
    fun duplicateDetected() = app {
        val c = jsonClient()
        val bytes = Random(2).nextBytes(2000)
        val first: StoredFile = c.upload(bytes).body()
        val second: StoredFile = c.upload(bytes).body()
        assertEquals(first.hash, second.hash)
        assertTrue(second.duplicate)
        assertEquals(1, fileCount())
    }

    @Test
    fun tooLarge413() = app(maxBytes = 1024) {
        val c = jsonClient()
        val r = c.upload(Random(3).nextBytes(2048))
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertTrue(r.bodyAsText().contains("\"file_too_large\""))
        assertEquals(0, fileCount())
    }

    @Test
    fun missingFilePart400() = app {
        val r = jsonClient().upload(Random(4).nextBytes(10), partName = "other")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals(0, fileCount())
    }

    @Test
    fun invalidHash400() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, c.get("/api/files/..%2Fsecret") { authed() }.status)
        assertEquals(HttpStatusCode.BadRequest, c.get("/api/files/ABC") { authed() }.status)
    }

    @Test
    fun unknownHash404() = app {
        val r = jsonClient().get("/api/files/" + "0".repeat(64)) { authed() }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun filesNeedAuth401() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/files/" + "0".repeat(64)).status)
        val r = c.submitFormWithBinaryData("/api/files", formData { append("file", byteArrayOf(1, 2, 3), Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"x.bin\"")
        }) }) { header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString()) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertEquals(0, fileCount())
    }

    @Test
    fun hugeUploadRejectedEarly413() = app(maxBytes = 1024) {
        val r = jsonClient().upload(Random(5).nextBytes(200 * 1024))
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertTrue(r.bodyAsText().contains("\"file_too_large\""))
        assertEquals(0, fileCount())
    }

    @Test
    fun emptyFileRoundTrip() = app {
        val c = jsonClient()
        val stored: StoredFile = c.upload(ByteArray(0)).body()
        assertEquals(sha256(ByteArray(0)), stored.hash)
        assertEquals(0L, stored.size)
        val d = c.get("/api/files/${stored.hash}") { authed() }
        assertEquals(HttpStatusCode.OK, d.status)
        assertEquals(0, d.bodyAsBytes().size)
        assertEquals("nosniff", d.headers["X-Content-Type-Options"])
    }

    @Test
    fun initRemovesLeftoverTempFiles() {
        val fd = filesDir()
        Files.createDirectories(fd)
        val tmp = Files.createFile(fd.resolve("upload-123.tmp"))
        val keep = Files.createFile(fd.resolve("0".repeat(64)))
        FileStore(fd, 1024)
        assertTrue(!Files.exists(tmp))
        assertTrue(Files.exists(keep))
    }
}
