package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.VAULT_PART_SIZE
import de.wanderlogplus.shared.sync.VaultImageInfo
import de.wanderlogplus.shared.sync.VaultKind
import de.wanderlogplus.shared.sync.VaultScanStatus
import de.wanderlogplus.shared.sync.VaultScanUpdate
import de.wanderlogplus.shared.sync.VaultUploadTrigger
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import java.nio.file.Files
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

    /** Same as [app], but hands the services over for direct store seeding. */
    private fun appWith(block: suspend ApplicationTestBuilder.(Services) -> Unit) = testApplication {
        val services = testServices(dir)
        application { module(services) }
        try {
            block(services)
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
    fun imageUploadIsIdempotent() = app {
        val c = jsonClient()
        val bytes = Random(7).nextBytes(8 * 1024)
        val first: VaultImageInfo = c.uploadImage(bytes, fileName = "same.jpg").body()
        // A retried upload (e.g. the app timed out after the server stored the file) must not duplicate.
        val second: VaultImageInfo = c.uploadImage(bytes, fileName = "same.jpg").body()
        assertEquals(first.id, second.id)
        assertEquals(listOf(first.id), c.get("/api/vault/images") { authed() }.body<List<VaultImageInfo>>().map { it.id })
        // Same content under another name is still the same image; different content is not.
        val renamed: VaultImageInfo = c.uploadImage(bytes, fileName = "other.jpg").body()
        assertEquals(first.id, renamed.id)
        val other: VaultImageInfo = c.uploadImage(Random(8).nextBytes(1024), fileName = "same.jpg").body()
        assertTrue(other.id != first.id)
        assertEquals(2, c.get("/api/vault/images") { authed() }.body<List<VaultImageInfo>>().size)
    }

    @Test
    fun uploadDedupsAgainstPreHashEntries() = appWith { services ->
        val c = jsonClient()
        val bytes = Random(9).nextBytes(2048)
        val old = services.vault.addImage("alice", bytes.inputStream(), "old.jpg", "image/jpeg", 0.5)
        // Simulate an entry from before hashes existed: strip the hash from the stored metadata.
        val meta = dir.resolve("vault/alice/images.json")
        val stripped = kotlinx.serialization.json.Json { explicitNulls = false }.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(VaultImageInfo.serializer()),
            services.vault.images("alice").map { it.copy(hash = null) },
        )
        java.nio.file.Files.writeString(meta, stripped)
        assertEquals(null, services.vault.images("alice").single().hash)

        val again: VaultImageInfo = c.uploadImage(bytes, fileName = "old.jpg").body()
        assertEquals(old.id, again.id)
        val all = services.vault.images("alice")
        assertEquals(1, all.size)
        assertTrue(all.single().hash != null) // backfilled during the check
    }

    @Test
    fun imageByHashFindsStoredImage() = app {
        val c = jsonClient()
        val bytes = Random(11).nextBytes(4096)
        val info: VaultImageInfo = c.uploadImage(bytes, fileName = "hashed.jpg").body()
        val hash = info.hash!!
        val found = c.get("/api/vault/images/by-hash/$hash") { authed() }
        assertEquals(HttpStatusCode.OK, found.status)
        assertEquals(info.id, found.body<VaultImageInfo>().id)
        // Hex case does not matter.
        assertEquals(HttpStatusCode.OK, c.get("/api/vault/images/by-hash/${hash.uppercase()}") { authed() }.status)
        // Other users' namespaces are not searched.
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/images/by-hash/$hash") { authed(TOKEN_B) }.status)
    }

    @Test
    fun imageByHashMissesAndValidates() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/images/by-hash/${"0".repeat(64)}") { authed() }.status)
        assertEquals(HttpStatusCode.BadRequest, c.get("/api/vault/images/by-hash/not-a-hash") { authed() }.status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/vault/images/by-hash/${"0".repeat(64)}").status)
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
        assertTrue(html.contains("Bilder-Upload"))
        assertTrue(html.contains("42 / 42"))
        assertTrue(html.contains("Pixel"))
    }

    @Test
    fun webPageShowsScoreOnlyWhenKnown() = app {
        val c = jsonClient()
        c.uploadImage(Random(13).nextBytes(128), score = "0.9", fileName = "scored.jpg")
        c.uploadImage(Random(14).nextBytes(128), score = "0", fileName = "plain.jpg")
        val html = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertTrue(html.contains("Score 90"))
        // Score 0 means "not scored" (full-backup upload) and stays hidden.
        assertTrue(!html.contains("Score 0"))
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
    fun uploadTriggerRoundTrip() = app {
        val c = jsonClient()
        assertEquals(false, c.get("/api/vault/uploads/request") { authed() }.body<VaultUploadTrigger>().requested)
        assertEquals(HttpStatusCode.NoContent, c.post("/api/vault/uploads/request") { authed() }.status)
        assertEquals(true, c.get("/api/vault/uploads/request") { authed() }.body<VaultUploadTrigger>().requested)
        // Reading does not consume: the app clears explicitly after scheduling (at-least-once).
        assertEquals(true, c.get("/api/vault/uploads/request") { authed() }.body<VaultUploadTrigger>().requested)
        assertEquals(HttpStatusCode.NoContent, c.post("/api/vault/uploads/request/clear") { authed() }.status)
        assertEquals(false, c.get("/api/vault/uploads/request") { authed() }.body<VaultUploadTrigger>().requested)
    }

    @Test
    fun uploadTriggerIsPerUserAndNeedsAuth() = app {
        val c = jsonClient()
        c.post("/api/vault/uploads/request") { authed() }
        assertEquals(false, c.get("/api/vault/uploads/request") { authed(TOKEN_B) }.body<VaultUploadTrigger>().requested)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/api/vault/uploads/request").status)
        assertEquals(HttpStatusCode.Unauthorized, c.post("/api/vault/uploads/request").status)
    }

    @Test
    fun webPageHasResumeButton() = app {
        val c = jsonClient()
        val html = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertTrue(html.contains("Upload fortsetzen"))
        assertTrue(html.contains("/api/vault/uploads/request"))
    }

    @Test
    fun vaultStateReportsImageAndScanFingerprint() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, c.get("/vault/state").status)
        assertEquals(
            """{"images":0,"latest":0,"scanUpdated":0,"requests":0,"sessionsProgress":0,"paused":false}""",
            c.get("/vault/state?token=$TOKEN_A").bodyAsText(),
        )

        val info: VaultImageInfo = c.uploadImage(Random(3).nextBytes(256)).body()
        val scan: VaultScanStatus = c.postScan(VaultScanUpdate("running", 10, 5, 1)).body()
        val body = c.get("/vault/state?token=$TOKEN_A").bodyAsText()
        assertEquals(
            """{"images":1,"latest":${info.uploadedAt},"scanUpdated":${scan.updatedAt},"requests":0,"sessionsProgress":0,"paused":false}""",
            body,
        )

        // Bob has his own namespace (no images, no scan).
        assertEquals(
            """{"images":0,"latest":0,"scanUpdated":0,"requests":0,"sessionsProgress":0,"paused":false}""",
            c.get("/vault/state?token=$TOKEN_B").bodyAsText(),
        )
    }

    @Test
    fun webPagePollsStateInsteadOfMetaRefresh() = app {
        val c = jsonClient()
        val html = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertTrue(!html.contains("http-equiv=\"refresh\""))
        assertTrue(html.contains("/vault/state"))
    }

    @Test
    fun webPagePaginatesImages() = appWith { services ->
        val c = jsonClient()
        val total = VAULT_PAGE_SIZE + 5
        repeat(total) { i ->
            val name = "img-%03d.jpg".format(i)
            services.vault.addImage("alice", name.byteInputStream(), name, "image/jpeg", 0.5)
            Thread.sleep(2) // distinct uploadedAt, so the newest-first order is deterministic
        }
        val p1 = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertEquals(VAULT_PAGE_SIZE, p1.split("<figure>").size - 1)
        assertTrue(p1.contains("Seite 1 von 2"))
        assertTrue(p1.contains("page=2"))
        assertTrue(p1.contains("Bilder ($total)"))
        assertTrue(p1.contains("img-%03d.jpg".format(total - 1)))
        assertTrue(!p1.contains("img-000.jpg"))
        val p2 = c.get("/vault?token=$TOKEN_A&page=2").bodyAsText()
        assertEquals(5, p2.split("<figure>").size - 1)
        assertTrue(p2.contains("Seite 2 von 2"))
        assertTrue(p2.contains("img-000.jpg"))
        // Out-of-range pages clamp to the last page instead of showing an empty grid.
        val p99 = c.get("/vault?token=$TOKEN_A&page=99").bodyAsText()
        assertEquals(5, p99.split("<figure>").size - 1)
    }

    @Test
    fun malformedImageIdIsSafe() = app {
        val c = jsonClient()
        assertEquals(HttpStatusCode.NotFound, c.get("/api/vault/images/..%2F..%2Fwanderlog-plus.db") { authed() }.status)
        assertEquals(HttpStatusCode.NotFound, c.get("/vault/img/..%2F..%2Fwanderlog-plus.db?token=$TOKEN_A").status)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Services.seedVideo(ref: String, name: String, seed: Int, durationMs: Long = 1000, videoBytes: Long = 100): VaultImageInfo =
        vault.addImage(
            "alice", byteArrayOf(seed.toByte()).inputStream(), name, "image/jpeg", 0.0,
            kind = VaultKind.VIDEO, videoRef = ref, durationMs = durationMs, videoBytes = videoBytes,
        )

    @Test
    fun webPageHasTabsSortAndStatusBar() = appWith { services ->
        val c = jsonClient()
        c.postScan(VaultScanUpdate("done", 10, 10, 2, photosOnDevice = 100, videosOnDevice = 7, pendingUploads = 42))
        services.vault.addImage("alice", byteArrayOf(1).inputStream(), "foto.jpg", "image/jpeg", 0.0)
        services.seedVideo("ref-1", "urlaub.jpg", seed = 2, durationMs = 390_000, videoBytes = 123_456_789)

        val html = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertTrue(html.contains("tab=videos"))
        assertTrue(html.contains("sort=size"))
        assertTrue(html.contains("statusbar"))
        assertTrue(html.contains("100 Fotos"))
        assertTrue(html.contains("7 Videos"))
        assertTrue(html.contains("42 ausstehend"))
        assertTrue(html.contains("/api/vault/uploads/pause"))
        // The photos tab hides the video; the videos tab shows it with its duration.
        assertTrue(!html.contains("urlaub.jpg"))
        val videos = c.get("/vault?token=$TOKEN_A&tab=videos").bodyAsText()
        assertTrue(videos.contains("urlaub.jpg"))
        assertTrue(videos.contains("6:30"))
    }

    @Test
    fun webVideoCardStates() = appWith { services ->
        val c = jsonClient()
        val plain = services.seedVideo("ref-plain", "plain.jpg", seed = 1)
        val requested = services.seedVideo("ref-req", "req.jpg", seed = 2)
        services.vault.requestVideo("alice", "ref-req")
        val uploading = services.seedVideo("ref-up", "up.jpg", seed = 3)
        // 42 of 100 parts received, written in the store's internal session format.
        val sessionId = "b".repeat(32)
        val sessionsDir = dir.resolve("vault/alice/sessions")
        Files.createDirectories(sessionsDir)
        Files.writeString(
            sessionsDir.resolve("$sessionId.json"),
            """{"id":"$sessionId","videoRef":"ref-up","fileName":"up.mp4","size":${100 * VAULT_PART_SIZE},"sha256":"${"0".repeat(64)}","videoMimeType":"video/mp4","partSize":$VAULT_PART_SIZE,"receivedParts":[${(0..41).joinToString(",")}],"createdAt":${System.currentTimeMillis()}}""",
        )
        val done = services.seedVideo("ref-done", "done.jpg", seed = 4)
        val content = byteArrayOf(5, 6, 7, 8)
        val s = services.vault.startVideoSession("alice", "ref-done", "done.mp4", content.size.toLong(), sha256Hex(content), "video/mp4")
        services.vault.savePart("alice", s.id, 0, content.inputStream())
        services.vault.completeVideoSession("alice", s.id)

        val html = c.get("/vault?token=$TOKEN_A&tab=videos").bodyAsText()
        // Not requested: thumbnail badge plus the button that queues the upload.
        assertTrue(html.contains("Thumbnail"), "thumbnail badge")
        assertTrue(html.contains("/api/vault/videos/${plain.id}/request"), "request button")
        // Queued, uploading, done.
        assertTrue(html.contains("angefordert"), "requested badge")
        assertTrue(html.contains("lädt 42 %"), "session progress badge")
        assertTrue(html.contains("/vault/vid/${done.id}"), "playback link")
        assertTrue(html.contains("geladen ▶"), "done badge")
        assertTrue(html.contains(uploading.fileName), "uploading card")
        assertTrue(html.contains(requested.fileName), "requested card")
    }

    @Test
    fun webVideoStreamingServesRanges() = appWith { services ->
        val c = jsonClient()
        val entry = services.seedVideo("ref-vid", "clip.jpg", seed = 9)
        val content = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val s = services.vault.startVideoSession("alice", "ref-vid", "clip.mp4", content.size.toLong(), sha256Hex(content), "video/mp4")
        services.vault.savePart("alice", s.id, 0, content.inputStream())
        services.vault.completeVideoSession("alice", s.id)

        val full = c.get("/vault/vid/${entry.id}?token=$TOKEN_A")
        assertEquals(HttpStatusCode.OK, full.status)
        assertEquals(ContentType.Video.MP4, full.contentType()?.withoutParameters())
        assertContentEquals(content, full.bodyAsBytes())

        // Browsers seek with Range headers; the plugin must answer 206 with a Content-Range.
        val range = c.get("/vault/vid/${entry.id}?token=$TOKEN_A") { header(HttpHeaders.Range, "bytes=0-3") }
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertTrue(range.headers[HttpHeaders.ContentRange]!!.startsWith("bytes 0-3/10"))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), range.bodyAsBytes())

        assertEquals(HttpStatusCode.NotFound, c.get("/vault/vid/${"0".repeat(32)}?token=$TOKEN_A").status)
        assertEquals(HttpStatusCode.Unauthorized, c.get("/vault/vid/${entry.id}").status)

        // An odd stored mime type falls back to a plain binary stream.
        val odd = services.seedVideo("ref-odd", "odd.jpg", seed = 8)
        val oddBytes = byteArrayOf(5, 6, 7, 8)
        val s2 = services.vault.startVideoSession("alice", "ref-odd", "odd.bin", oddBytes.size.toLong(), sha256Hex(oddBytes), "not a mime")
        services.vault.savePart("alice", s2.id, 0, oddBytes.inputStream())
        services.vault.completeVideoSession("alice", s2.id)
        val r2 = c.get("/vault/vid/${odd.id}?token=$TOKEN_A")
        assertEquals(HttpStatusCode.OK, r2.status)
        assertEquals(ContentType.Application.OctetStream, r2.contentType()?.withoutParameters())
    }

    @Test
    fun webPageBulkDeleteMarkup() = app {
        val c = jsonClient()
        val info: VaultImageInfo = c.uploadImage(Random(15).nextBytes(64)).body()
        val html = c.get("/vault?token=$TOKEN_A").bodyAsText()
        assertTrue(html.contains("""name="sel""""))
        assertTrue(html.contains("""value="${info.id}""""))
        assertTrue(html.contains("/api/vault/images/bulk-delete"))
    }
}
