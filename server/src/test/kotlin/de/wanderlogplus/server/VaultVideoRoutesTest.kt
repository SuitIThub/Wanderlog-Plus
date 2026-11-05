package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.VAULT_PART_SIZE
import de.wanderlogplus.shared.sync.VaultImageInfo
import de.wanderlogplus.shared.sync.VaultKind
import de.wanderlogplus.shared.sync.VaultUploadTrigger
import de.wanderlogplus.shared.sync.VaultVideoSession
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JSON API of the chunked video uploads, the per-video request queue, pause and bulk delete. */
class VaultVideoRoutesTest {
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Uploads a thumbnail with video metadata; returns the created entry. */
    private suspend fun io.ktor.client.HttpClient.uploadVideoThumb(
        ref: String,
        fileName: String = "clip.jpg",
        token: String = TOKEN_A,
    ): VaultImageInfo =
        submitFormWithBinaryData(
            url = "/api/vault/images",
            formData = formData {
                append("score", "0")
                append("fileName", fileName)
                append("mimeType", "image/jpeg")
                append("kind", "video")
                append("videoRef", ref)
                append("durationMs", "6500")
                append("videoBytes", "123456")
                append("file", byteArrayOf(1, 2, 3), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        ) { authed(token) }.body()

    private suspend fun io.ktor.client.HttpClient.initSession(
        ref: String,
        size: Long,
        sha256: String,
        fileName: String = "clip.mp4",
    ): HttpResponse =
        post("/api/vault/videos/sessions") {
            authed()
            contentType(ContentType.Application.Json)
            setBody("""{"videoRef":"$ref","fileName":"$fileName","size":$size,"sha256":"$sha256","videoMimeType":"video/mp4"}""")
        }

    private suspend fun io.ktor.client.HttpClient.putPart(
        sessionId: String,
        index: Int,
        bytes: ByteArray,
        sha256: String = sha256Hex(bytes),
    ): HttpResponse =
        put("/api/vault/videos/sessions/$sessionId/parts/$index") {
            authed()
            contentType(ContentType.Application.OctetStream)
            header("X-Part-Sha256", sha256)
            setBody(bytes)
        }

    @Test
    fun sessionHappyPathOverHttp() = app {
        val c = jsonClient()
        val ref = "content://media/external/video/1"
        val entry = c.uploadVideoThumb(ref)
        assertEquals(VaultKind.VIDEO, entry.kind)
        assertEquals(ref, entry.videoRef)

        // Two parts: one full part plus a tail.
        val content = Random(42).nextBytes(VAULT_PART_SIZE.toInt() + 100)
        val hash = sha256Hex(content)
        val session: VaultVideoSession = c.initSession(ref, content.size.toLong(), hash).let {
            assertEquals(HttpStatusCode.OK, it.status)
            it.body()
        }
        assertFalse(session.complete)
        assertEquals(VAULT_PART_SIZE, session.partSize)
        assertEquals(emptyList(), session.receivedParts)

        val p0 = c.putPart(session.id, 0, content.copyOfRange(0, VAULT_PART_SIZE.toInt()))
        assertEquals(HttpStatusCode.OK, p0.status)
        assertEquals(listOf(0), p0.body<VaultVideoSession>().receivedParts)
        val p1 = c.putPart(session.id, 1, content.copyOfRange(VAULT_PART_SIZE.toInt(), content.size))
        assertEquals(HttpStatusCode.OK, p1.status)

        val done = c.post("/api/vault/videos/sessions/${session.id}/complete") { authed() }
        assertEquals(HttpStatusCode.OK, done.status)
        val info: VaultImageInfo = done.body()
        assertEquals(entry.id, info.id)
        assertTrue(info.hasVideo)
        assertEquals(hash, info.videoHash)
        assertEquals("video/mp4", info.videoMimeType)

        // The entry as listed carries the video state too.
        val listed = c.get("/api/vault/images") { authed() }.body<List<VaultImageInfo>>().single()
        assertTrue(listed.hasVideo)
    }

    @Test
    fun unknownSessionIsNotFound() = app {
        val c = jsonClient()
        val gone = "0".repeat(32)
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/videos/sessions/$gone") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, c.putPart(gone, 0, byteArrayOf(1)).status)
        assertEquals(HttpStatusCode.NotFound, c.post("/api/vault/videos/sessions/$gone/complete") { authed() }.status)
        val body = c.get("/api/vault/videos/sessions/$gone") { authed() }.bodyAsText()
        assertEquals("not_found", kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun conflictingPartIs409AndBadIndexIs400() = app {
        val c = jsonClient()
        val ref = "r"
        c.uploadVideoThumb(ref)
        val content = ByteArray(100) { it.toByte() }
        val session: VaultVideoSession = c.initSession(ref, content.size.toLong(), sha256Hex(content)).body()

        assertEquals(HttpStatusCode.OK, c.putPart(session.id, 0, content).status)
        // Same index, different bytes: the source changed mid-upload.
        val conflict = c.putPart(session.id, 0, ByteArray(100) { 9 })
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertTrue(conflict.bodyAsText().contains("part_conflict"))
        // Index beyond the part count is rejected outright.
        assertEquals(HttpStatusCode.BadRequest, c.putPart(session.id, 1, byteArrayOf(1)).status)
        // A forged part hash does not store anything either.
        assertEquals(HttpStatusCode.BadRequest, c.putPart(session.id, 0, content, sha256 = "0".repeat(64)).status)
    }

    @Test
    fun requestUnrequestPauseRoundTrip() = app {
        val c = jsonClient()
        val ref = "r"
        val entry = c.uploadVideoThumb(ref)

        suspend fun trigger(token: String = TOKEN_A): VaultUploadTrigger =
            c.get("/api/vault/uploads/request") { authed(token) }.let {
                assertEquals(HttpStatusCode.OK, it.status)
                it.body()
            }

        assertEquals(VaultUploadTrigger(false), trigger())
        assertEquals(HttpStatusCode.NoContent, c.post("/api/vault/videos/${entry.id}/request") { authed() }.status)
        assertEquals(listOf(ref), trigger().videoRefs)
        assertEquals(HttpStatusCode.NoContent, c.post("/api/vault/uploads/pause") { authed() }.status)
        assertTrue(trigger().paused)
        assertEquals(HttpStatusCode.NoContent, c.post("/api/vault/uploads/resume") { authed() }.status)
        assertFalse(trigger().paused)
        assertEquals(HttpStatusCode.NoContent, c.post("/api/vault/videos/${entry.id}/unrequest") { authed() }.status)
        assertEquals(emptyList(), trigger().videoRefs)

        // Requests only exist for VIDEO entries.
        val photo: VaultImageInfo = c.submitFormWithBinaryData(
            url = "/api/vault/images",
            formData = formData {
                append("fileName", "p.jpg")
                append("mimeType", "image/jpeg")
                append("file", byteArrayOf(4, 5, 6), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"p.jpg\"")
                })
            },
        ) { authed() }.body()
        assertEquals(HttpStatusCode.NotFound, c.post("/api/vault/videos/${photo.id}/request") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, c.post("/api/vault/videos/${"0".repeat(32)}/request") { authed() }.status)

        // Bob's namespace is untouched; unauthenticated calls fail.
        assertEquals(VaultUploadTrigger(false), trigger(TOKEN_B))
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/vault/uploads/request").status)
        assertEquals(HttpStatusCode.Unauthorized, c.post("/api/vault/uploads/pause").status)
        assertEquals(HttpStatusCode.Unauthorized, c.post("/api/vault/videos/${entry.id}/request").status)
    }

    @Test
    fun bulkDeleteRemovesExistingIds() = app {
        val c = jsonClient()
        val ids = (1..3).map { i ->
            c.submitFormWithBinaryData(
                url = "/api/vault/images",
                formData = formData {
                    append("fileName", "p$i.jpg")
                    append("mimeType", "image/jpeg")
                    append("file", byteArrayOf(i.toByte()), Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"p$i.jpg\"")
                    })
                },
            ) { authed() }.body<VaultImageInfo>().id
        }
        val r = c.post("/api/vault/images/bulk-delete") {
            authed()
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["${ids[0]}","${ids[1]}","${"0".repeat(32)}"]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains(""""deleted":2"""))
        assertEquals(ids[2], c.get("/api/vault/images") { authed() }.body<List<VaultImageInfo>>().single().id)
    }

    @Test
    fun imageUploadStoresVideoMetadata() = app {
        val c = jsonClient()
        val ref = "content://media/external/video/9"
        val entry = c.uploadVideoThumb(ref)
        assertEquals(VaultKind.VIDEO, entry.kind)
        assertEquals(ref, entry.videoRef)
        assertEquals(6500, entry.durationMs)
        assertEquals(123456, entry.videoBytes)
        assertFalse(entry.hasVideo)
    }

    @Test
    fun initSessionValidatesInput() = app {
        val c = jsonClient()
        val valid = "a".repeat(64)
        assertEquals(HttpStatusCode.BadRequest, c.initSession("r", 10, "not-a-hash").status)
        assertEquals(HttpStatusCode.BadRequest, c.initSession("r", 0, valid).status)
        assertEquals(HttpStatusCode.BadRequest, c.initSession("", 10, valid).status)
        // Uppercase hex is fine (normalized to lowercase).
        assertEquals(HttpStatusCode.OK, c.initSession("r", 10, valid.uppercase()).status)
        assertEquals(HttpStatusCode.Unauthorized, c.post("/api/vault/videos/sessions").status)
    }
}
