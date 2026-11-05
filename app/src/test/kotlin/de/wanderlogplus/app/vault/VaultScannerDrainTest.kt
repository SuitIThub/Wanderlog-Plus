package de.wanderlogplus.app.vault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.VaultFoundEntity
import de.wanderlogplus.app.data.VaultVideoRequestEntity
import de.wanderlogplus.client.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The upload drain's by-hash dance: before a body crawls up a slow link, ask whether the server already
 * has these bytes (an earlier attempt may have stored them while its response got lost). A failed upload
 * gets one cheap re-check for the same reason before the pass gives up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultScannerDrainTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val infoJson =
        """{"id":"i1","fileName":"a.jpg","mimeType":"image/jpeg","size":3,"score":0.9,"uploadedAt":7,"hash":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}"""
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private val thumbHash = "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"
    private val videoBytes = ByteArray(20) { it.toByte() }
    private val videoHash = "e7aebf577f60412f0312d442c70a1fa6148c090bf5bab404caec29482ae779e8"
    private val partHashes = listOf(
        "8a851ff82ee7048ad09ec3847f1ddf44944104d2cbd17ef4e3db22c6785a0d45",
        "34179de5450f6796386cb746cf0608b2a3356e937fb69e5e9ac0c6c29ef61a05",
        "a00291e8229d191815f2cbd2aa49d3b783585deae8012ca5941f011ceb9eb119",
    )
    private val sessionJson =
        """{"id":"s1","videoRef":"content://video/1","fileName":"clip.mp4","size":20,"sha256":"$videoHash","partSize":8,"receivedParts":[],"complete":false}"""
    private val triggerJson = """{"requested":false,"paused":false,"videoRefs":[]}"""

    private fun scanner(
        thumbnails: VideoThumbnails = VideoThumbnails { null },
        openStream: (android.net.Uri) -> java.io.InputStream? = { "abc".byteInputStream() },
        fileLength: (android.net.Uri) -> Long? = { null },
        partSize: Long = de.wanderlogplus.shared.sync.VAULT_PART_SIZE,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): VaultScanner =
        VaultScanner(
            appContext = ApplicationProvider.getApplicationContext(),
            dao = db.vault(),
            api = { SyncApi(HttpClient(MockEngine(handler)), "https://s.example", "tok") },
            openStream = openStream,
            thumbnails = thumbnails,
            fileLength = fileLength,
            partSize = partSize,
        )

    private fun videoScanner(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        scanner(
            openStream = { java.io.ByteArrayInputStream(videoBytes) },
            fileLength = { 20L },
            partSize = 8,
            handler = handler,
        )

    private fun seed(uri: String = "content://media/1") =
        db.vault().insertFound(VaultFoundEntity(uri, "a.jpg", "image/jpeg", 0.9, foundAt = 1, uploadedAt = null))

    private fun seedVideo(uri: String = "content://video/1") =
        db.vault().insertFound(
            VaultFoundEntity(uri, "clip.mp4", "video/mp4", 0.0, foundAt = 1, uploadedAt = null, kind = "video", durationMs = 6500, sizeBytes = 123456),
        )

    /** Thumbnail already on the server; the full file is queued for a chunked upload. */
    private fun queueFullVideo(uri: String = "content://video/1", sizeBytes: Long = 999) {
        db.vault().insertFound(
            VaultFoundEntity(uri, "clip.mp4", "video/mp4", 0.0, foundAt = 1, uploadedAt = 1, kind = "video", durationMs = 6500, sizeBytes = sizeBytes),
        )
        db.vault().insertVideoRequest(VaultVideoRequestEntity(uri, 1))
    }

    private fun MockRequestHandleScope.session(received: List<Int> = emptyList(), complete: Boolean = false, id: String = "s1") =
        respond(
            """{"id":"$id","videoRef":"content://video/1","fileName":"clip.mp4","size":20,"sha256":"$videoHash","partSize":8,"receivedParts":[${received.joinToString(",")}],"complete":$complete}""",
            HttpStatusCode.OK,
            json,
        )

    @Test fun serverAlreadyHasHashSkipsUpload() = runBlocking {
        seed()
        var posts = 0
        val out = scanner { req ->
            if (req.method == HttpMethod.Post) posts++
            respond(infoJson, HttpStatusCode.OK, json)
        }.drainUploads()
        assertEquals(1, out.uploaded)
        assertEquals(0, posts)
        assertEquals(0, out.remaining)
        assertEquals(0, db.vault().pendingUploadCount())
    }

    @Test fun hashMissUploadsBody() = runBlocking {
        seed()
        var posts = 0
        val out = scanner { req ->
            when {
                req.url.encodedPath.contains("/by-hash/") -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
                else -> { posts++; respond(infoJson, HttpStatusCode.OK, json) }
            }
        }.drainUploads()
        assertEquals(1, out.uploaded)
        assertEquals(1, posts)
        assertEquals(0, db.vault().pendingUploadCount())
    }

    @Test fun lostResponseIsRecoveredByHashRecheck() = runBlocking {
        seed()
        var hashChecks = 0
        val out = scanner { req ->
            if (req.url.encodedPath.contains("/by-hash/")) {
                hashChecks++
                if (hashChecks == 1) respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
                else respond(infoJson, HttpStatusCode.OK, json)
            } else {
                // The upload dies after the server stored the file (slow relay ate the response).
                respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, json)
            }
        }.drainUploads()
        assertEquals(1, out.uploaded)
        assertFalse(out.stoppedOnError)
        assertEquals(2, hashChecks)
        assertEquals(0, db.vault().pendingUploadCount())
    }

    @Test fun unrecoveredUploadFailureStopsDrain() = runBlocking {
        seed()
        seed("content://media/2")
        val out = scanner { req ->
            if (req.url.encodedPath.contains("/by-hash/")) respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            else respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, json)
        }.drainUploads()
        assertEquals(0, out.uploaded)
        assertTrue(out.stoppedOnError)
        assertEquals(2, db.vault().pendingUploadCount()) // nothing behind the failed item is attempted
    }

    @Test fun permanentRejectionDropsWithoutRecheck() = runBlocking {
        seed()
        var hashChecks = 0
        val out = scanner { req ->
            if (req.url.encodedPath.contains("/by-hash/")) {
                hashChecks++
                respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            } else {
                respond("""{"error":"file_too_large"}""", HttpStatusCode.PayloadTooLarge, json)
            }
        }.drainUploads()
        assertEquals(0, out.uploaded)
        assertFalse(out.stoppedOnError)
        assertEquals(1, hashChecks) // permanent rejections are not re-checked
        assertEquals(0, db.vault().pendingUploadCount()) // but dropped so the queue is not blocked
    }

    @Test fun videoThumbnailIsUploadedWithMetadata() = runBlocking {
        seedVideo()
        var hashPath = ""
        var body = ""
        val out = scanner(thumbnails = VideoThumbnails { byteArrayOf(1, 2, 3) }) { req ->
            when {
                req.url.encodedPath.contains("/by-hash/") -> {
                    hashPath = req.url.encodedPath
                    respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
                }
                else -> {
                    body = String(req.body.toByteArray())
                    respond(infoJson, HttpStatusCode.OK, json)
                }
            }
        }.drainUploads()
        assertEquals(1, out.uploaded)
        assertTrue("hash $hashPath", hashPath.endsWith("/by-hash/$thumbHash"))
        assertTrue(body.contains("name=kind\r\n") && body.contains("\r\n\r\nvideo\r\n"))
        assertTrue(body.contains("name=videoRef\r\n") && body.contains("\r\n\r\ncontent://video/1\r\n"))
        assertTrue(body.contains("name=durationMs\r\n") && body.contains("\r\n\r\n6500\r\n"))
        assertTrue(body.contains("name=videoBytes\r\n") && body.contains("\r\n\r\n123456\r\n"))
        assertEquals(0, db.vault().pendingUploadCount())
    }

    @Test fun nullThumbnailDropsWithoutPost() = runBlocking {
        seedVideo()
        var posts = 0
        val out = scanner(thumbnails = VideoThumbnails { null }) { req ->
            if (req.method == HttpMethod.Post) posts++
            respond(infoJson, HttpStatusCode.OK, json)
        }.drainUploads()
        assertEquals(0, out.uploaded)
        assertEquals(0, posts)
        assertEquals(0, db.vault().pendingUploadCount())
    }

    @Test fun videoUploadsInPartsAndCompletes() = runBlocking {
        queueFullVideo()
        val puts = mutableListOf<Int>()
        val hashes = mutableListOf<String>()
        var inits = 0
        var completes = 0
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> { inits++; session() }
                p.contains("/parts/") -> {
                    val n = p.substringAfter("/parts/").toInt()
                    puts += n
                    hashes += req.headers["X-Part-Sha256"].orEmpty()
                    session(received = (0..n).toList())
                }
                p.endsWith("/complete") -> { completes++; respond(infoJson, HttpStatusCode.OK, json) }
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(1, out.uploaded)
        assertEquals(1, inits)
        assertEquals(listOf(0, 1, 2), puts)
        assertEquals(partHashes, hashes)
        assertEquals(1, completes)
        assertTrue(db.vault().videoRequestRefs().isEmpty())
    }

    @Test fun videoResumeSkipsReceivedParts() = runBlocking {
        queueFullVideo()
        val puts = mutableListOf<Int>()
        videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> session(received = listOf(0))
                p.contains("/parts/") -> { puts += p.substringAfter("/parts/").toInt(); session(received = listOf(0, 1, 2)) }
                p.endsWith("/complete") -> respond(infoJson, HttpStatusCode.OK, json)
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(listOf(1, 2), puts)
        assertTrue(db.vault().videoRequestRefs().isEmpty())
    }

    @Test fun pauseBetweenPartsStopsCleanly() = runBlocking {
        queueFullVideo()
        var puts = 0
        var triggerCalls = 0
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> {
                    triggerCalls++
                    val paused = triggerCalls > 1
                    respond("""{"requested":false,"paused":$paused,"videoRefs":[]}""", HttpStatusCode.OK, json)
                }
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> session()
                p.contains("/parts/") -> { puts++; session(received = listOf(0)) }
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(1, puts)
        assertFalse(out.stoppedOnError)
        assertTrue(out.paused)
        assertEquals(1, out.remaining) // still queued: the worker must not chain while paused
        assertEquals(1, db.vault().videoRequestRefs().size)
    }

    @Test fun remainingIncludesQueuedVideosSoWorkersChain() = runBlocking {
        queueFullVideo("content://video/1")
        queueFullVideo("content://video/2")
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> session(complete = true, id = "")
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(1, out.uploaded)
        assertEquals(1, out.remaining)
        assertFalse(out.paused)
        assertEquals(1, db.vault().videoRequestRefs().size)
    }

    @Test fun staleSessionIsReinitialized() = runBlocking {
        queueFullVideo()
        var inits = 0
        var firstPut = true
        val puts = mutableListOf<Int>()
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> { inits++; session(id = "s$inits") }
                p.contains("/parts/") -> {
                    if (firstPut) {
                        firstPut = false
                        respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
                    } else {
                        puts += p.substringAfter("/parts/").toInt()
                        session(id = "s2")
                    }
                }
                p.endsWith("/complete") -> respond(infoJson, HttpStatusCode.OK, json)
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(2, inits)
        assertEquals(listOf(0, 1, 2), puts)
        assertEquals(1, out.uploaded)
    }

    @Test fun partConflictRetriesOnce() = runBlocking {
        queueFullVideo()
        var part1 = 0
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> session()
                p.endsWith("/parts/1") -> {
                    part1++
                    if (part1 == 1) respond("""{"error":"part_conflict"}""", HttpStatusCode.Conflict, json)
                    else session(received = listOf(0, 1))
                }
                p.contains("/parts/") -> session()
                p.endsWith("/complete") -> respond(infoJson, HttpStatusCode.OK, json)
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(2, part1)
        assertEquals(1, out.uploaded)
    }

    @Test fun partConflictPersistentStopsDrain() = runBlocking {
        queueFullVideo()
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> session()
                p.endsWith("/parts/1") -> respond("""{"error":"part_conflict"}""", HttpStatusCode.Conflict, json)
                p.contains("/parts/") -> session()
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertTrue(out.stoppedOnError)
        assertEquals(0, out.uploaded)
        assertEquals(1, db.vault().videoRequestRefs().size)
    }

    @Test fun initDedupSkipsUpload() = runBlocking {
        queueFullVideo()
        var puts = 0
        val out = videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> session(complete = true, id = "")
                p.contains("/parts/") -> { puts++; session() }
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(0, puts)
        assertEquals(1, out.uploaded)
        assertTrue(db.vault().videoRequestRefs().isEmpty())
    }

    @Test fun declaredSizeComesFromStreamLength() = runBlocking {
        queueFullVideo(sizeBytes = 999)
        var initBody = ""
        videoScanner { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/uploads/request") -> respond(triggerJson, HttpStatusCode.OK, json)
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> {
                    initBody = String(req.body.toByteArray())
                    session(complete = true, id = "")
                }
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertTrue(initBody.contains("\"size\":20"))
        assertTrue(!initBody.contains("\"size\":999"))
    }

    @Test fun vanishedVideoIsUnrequestedAndSkipped() = runBlocking {
        queueFullVideo()
        var unrequests = 0
        var inits = 0
        val out = scanner(
            openStream = { null },
            fileLength = { null },
            partSize = 8,
        ) { req ->
            val p = req.url.encodedPath
            when {
                p.endsWith("/unrequest") -> { unrequests++; respond("", HttpStatusCode.NoContent) }
                p.endsWith("/sessions") && req.method == HttpMethod.Post -> { inits++; session() }
                else -> respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json)
            }
        }.drainUploads()
        assertEquals(1, unrequests)
        assertEquals(0, inits)
        assertEquals(0, out.uploaded)
        assertTrue(db.vault().videoRequestRefs().isEmpty())
    }
}
