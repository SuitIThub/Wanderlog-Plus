package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.VaultImageInfo
import de.wanderlogplus.shared.sync.VaultScanStatus
import de.wanderlogplus.shared.sync.VaultScanUpdate
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultRoutesTest {
    @TempDir
    lateinit var dir: Path

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val services = testServices(dir)
        application { module(services) }
        try {
            block()
        } finally {
            services.store.close()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private suspend fun io.ktor.client.HttpClient.uploadImage(
        bytes: ByteArray,
        score: String = "0.9",
        fileName: String = "x.jpg",
        mimeType: String = "image/jpeg",
        token: String = TOKEN_A,
    ): HttpResponse =
        submitFormWithBinaryData(
            url = "/api/vault/images",
            formData = formData {
                append("score", score)
                append("fileName", fileName)
                append("mimeType", mimeType)
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mimeType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        ) { authed(token) }

    private suspend fun io.ktor.client.HttpClient.postScan(update: VaultScanUpdate, token: String = TOKEN_A): HttpResponse =
        post("/api/vault/scan") {
            authed(token)
            contentType(ContentType.Application.Json)
            setBody(update)
        }

    @Test
    fun scanStatusRoundTrip() = app {
        val c = jsonClient()
        val r = c.postScan(VaultScanUpdate("running", totalImages = 100, scannedImages = 40, foundImages = 3, device = "Pixel"))
        assertEquals(HttpStatusCode.OK, r.status)
        val status: VaultScanStatus = r.body()
        assertEquals("alice", status.user)
        assertEquals("running", status.state)
        assertEquals(40, status.scannedImages)
        assertTrue(status.updatedAt > 0)
        val g = c.get("/api/vault/scan") { authed() }
        assertEquals(HttpStatusCode.OK, g.status)
        assertEquals(3, g.body<VaultScanStatus>().foundImages)
    }

    @Test
    fun scanStatusValidates() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, c.postScan(VaultScanUpdate("bogus", 1, 1, 1)).status)
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/scan") { authed() }.status)
    }

    @Test
    fun imageUploadListGetDelete() = app {
        val c = jsonClient()
        val bytes = Random(1).nextBytes(5 * 1024)
        val up = c.uploadImage(bytes, score = "0.75", fileName = "foto.jpg")
        assertEquals(HttpStatusCode.OK, up.status)
        val info: VaultImageInfo = up.body()
        assertEquals("foto.jpg", info.fileName)
        assertEquals(0.75, info.score)
        assertEquals(bytes.size.toLong(), info.size)

        val list: List<VaultImageInfo> = c.get("/api/vault/images") { authed() }.body()
        assertEquals(listOf(info.id), list.map { it.id })

        val img = c.get("/api/vault/images/${info.id}") { authed() }
        assertEquals(HttpStatusCode.OK, img.status)
        assertEquals(ContentType.Image.JPEG, img.contentType()?.withoutParameters())
        assertContentEquals(bytes, img.bodyAsBytes())
        assertEquals("nosniff", img.headers["X-Content-Type-Options"])

        assertEquals(HttpStatusCode.NoContent, c.delete("/api/vault/images/${info.id}") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/images/${info.id}") { authed() }.status)
        assertEquals(emptyList(), c.get("/api/vault/images") { authed() }.body<List<VaultImageInfo>>())
    }

    @Test
    fun perUserIsolation() = app {
        val c = jsonClient()
        val info: VaultImageInfo = c.uploadImage(Random(2).nextBytes(1024)).body()
        // Bob has his own empty namespace and cannot reach Alice's image.
        assertEquals(emptyList(), c.get("/api/vault/images") { authed(TOKEN_B) }.body<List<VaultImageInfo>>())
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/images/${info.id}") { authed(TOKEN_B) }.status)
        assertEquals(HttpStatusCode.NotFound, c.delete("/api/vault/images/${info.id}") { authed(TOKEN_B) }.status)
        c.postScan(VaultScanUpdate("done", 10, 10, 1), token = TOKEN_B)
        assertEquals("bob", c.get("/api/vault/scan") { authed(TOKEN_B) }.body<VaultScanStatus>().user)
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/scan") { authed() }.status)
    }

    @Test
    fun rejectsNonImageAndOversize() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, c.uploadImage(Random(3).nextBytes(100), mimeType = "text/plain").status)
        assertEquals(HttpStatusCode.PayloadTooLarge, c.uploadImage(Random(4).nextBytes(30 * 1024 * 1024)).status)
        assertEquals(emptyList(), c.get("/api/vault/images") { authed() }.body<List<VaultImageInfo>>())
    }

    @Test
    fun apiNeedsAuth() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/vault/images").status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/vault/scan").status)
    }

    @Test
    fun webPageNeedsTokenAndShowsScan() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/vault").status)
        c.postScan(VaultScanUpdate("done", 42, 42, 2, device = "Pixel"))
        val page = c.get("/vault?token=$TOKEN_A")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("Sensitiv-Scan"))
        assertTrue(html.contains("42 / 42"))
        assertTrue(html.contains("Pixel"))
    }

    @Test
    fun webPageServesImagesAndEscapesNames() = app {
        val c = jsonClient()
        val bytes = Random(5).nextBytes(512)
        val info: VaultImageInfo = c.uploadImage(bytes, fileName = "<b>bold</b>.jpg").body()
        val html = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt;.jpg"))
        assertTrue(!html.contains("<b>bold</b>"))
        val img = c.get("/vault/img/${info.id}?token=$TOKEN_A")
        assertEquals(HttpStatusCode.OK, img.status)
        assertContentEquals(bytes, img.bodyAsBytes())
        assertEquals(HttpStatusCode.Unauthorized, c.get("/vault/img/${info.id}").status)
        // Another user's web token does not unlock the image either.
        assertEquals(HttpStatusCode.NotFound, c.get("/vault/img/${info.id}?token=$TOKEN_B").status)
    }

    @Test
    fun malformedImageIdIsSafe() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/images/..%2F..%2Fwanderlog-plus.db") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, c.get("/vault/img/..%2F..%2Fwanderlog-plus.db?token=$TOKEN_A").status)
    }
}
