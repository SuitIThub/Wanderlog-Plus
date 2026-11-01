package de.wanderlogplus.app.files

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.PendingUploadEntity
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Attachment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileSyncRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private lateinit var db: AppDatabase
    private lateinit var files: AttachmentFiles

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        files = AttachmentFiles(tmp.newFolder("att"))
    }

    @After fun tearDown() { db.close() }

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        SyncApi(HttpClient(MockEngine(handler)), "https://s.example", "tok")

    private fun runner(api: SyncApi?, atts: List<Attachment> = emptyList()) =
        FileSyncRunner({ api }, files, db.pendingUploads(), { atts })

    @Test fun uploadsPendingAndDeletesRow() = runBlocking {
        val imported = files.importStream("abc".byteInputStream())
        db.pendingUploads().insert(PendingUploadEntity(imported.hash, "a.txt", "text/plain", 1))
        var posts = 0
        val r = runner(api { req ->
            assertEquals(HttpMethod.Post, req.method)
            posts++
            respond("""{"hash":"$abc","size":3,"duplicate":false}""", HttpStatusCode.OK, json)
        }).run()
        assertEquals(FileSyncResult(1, 0, 0), r)
        assertEquals(1, posts)
        assertTrue(db.pendingUploads().all().isEmpty())
    }

    @Test fun missingLocalFileDropsRowAndCountsFailed() = runBlocking {
        db.pendingUploads().insert(PendingUploadEntity(abc, "a.txt", "text/plain", 1))
        val r = runner(api { respond("{}", HttpStatusCode.OK, json) }).run()
        assertEquals(1, r.failed)
        assertTrue(db.pendingUploads().all().isEmpty())
    }

    @Test fun downloadsMissingAttachments() = runBlocking {
        val r = runner(api { respond("abc".toByteArray(), HttpStatusCode.OK) }, listOf(Attachment(id = "a1", fileHash = abc))).run()
        assertEquals(FileSyncResult(0, 1, 0), r)
        assertEquals("abc", files.fileFor(abc)!!.readText())
    }

    @Test fun missingOnServerCountsAsFailedAndRetriesLater() = runBlocking {
        var gets = 0
        val runner = runner(api { gets++; respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, json) }, listOf(Attachment(id = "a1", fileHash = abc)))
        val r = runner.run()
        assertEquals(1, r.failed)
        assertFalse(r.retryable)
        assertNull(files.fileFor(abc))
        runner.run()
        assertEquals(2, gets)
    }

    @Test fun partialDownloadLeavesNoFile() = runBlocking {
        val r = runner(api { respond("abX".toByteArray(), HttpStatusCode.OK) }, listOf(Attachment(id = "a1", fileHash = abc))).run()
        assertEquals(1, r.failed)
        assertTrue(r.retryable)
        assertNull(files.fileFor(abc))
    }

    @Test fun noServerDoesNothing() = runBlocking {
        db.pendingUploads().insert(PendingUploadEntity(abc, "a.txt", "text/plain", 1))
        assertEquals(FileSyncResult(0, 0, 0), runner(null, listOf(Attachment(id = "a1", fileHash = abc))).run())
        assertEquals(1, db.pendingUploads().all().size)
    }

    @Test fun downloadCreatesMissingDirectory() = runBlocking {
        files = AttachmentFiles(java.io.File(tmp.root, "fresh/attachments"))
        val r = runner(api { respond("abc".toByteArray(), HttpStatusCode.OK) }, listOf(Attachment(id = "a1", fileHash = abc))).run()
        assertEquals(FileSyncResult(0, 1, 0), r)
        assertNotNull(files.fileFor(abc))
    }

    @Test fun rejectedUploadIsDroppedAndNotRetryable() = runBlocking {
        val imported = files.importStream("abc".byteInputStream())
        db.pendingUploads().insert(PendingUploadEntity(imported.hash, "a.txt", "text/plain", 1))
        val r = runner(api { respond("""{"error":"file_too_large"}""", HttpStatusCode.PayloadTooLarge, json) }).run()
        assertEquals(1, r.failed)
        assertFalse(r.retryable)
        assertTrue(db.pendingUploads().all().isEmpty())
    }

    @Test fun proxy413WithoutBodyKeepsRowAndIsNotRetryable() = runBlocking {
        val imported = files.importStream("abc".byteInputStream())
        db.pendingUploads().insert(PendingUploadEntity(imported.hash, "a.txt", "text/plain", 1))
        val r = runner(api { respond("", HttpStatusCode.PayloadTooLarge) }).run()
        assertEquals(1, r.failed)
        assertFalse(r.retryable)
        assertEquals(1, db.pendingUploads().all().size)
    }

    @Test fun tooManyRequestsKeepsRowAndIsRetryable() = runBlocking {
        val imported = files.importStream("abc".byteInputStream())
        db.pendingUploads().insert(PendingUploadEntity(imported.hash, "a.txt", "text/plain", 1))
        val r = runner(api { respond("""{"error":"rate_limited"}""", HttpStatusCode.TooManyRequests, json) }).run()
        assertEquals(1, r.failed)
        assertTrue(r.retryable)
        assertEquals(1, db.pendingUploads().all().size)
    }

    @Test fun otherClientErrorKeepsRowAndIsNotRetryable() = runBlocking {
        val imported = files.importStream("abc".byteInputStream())
        db.pendingUploads().insert(PendingUploadEntity(imported.hash, "a.txt", "text/plain", 1))
        val r = runner(api { respond("""{"error":"bad_request"}""", HttpStatusCode.BadRequest, json) }).run()
        assertEquals(1, r.failed)
        assertFalse(r.retryable)
        assertEquals(1, db.pendingUploads().all().size)
    }

    @Test fun invalidAttachmentHashesAreSkipped() = runBlocking {
        var requests = 0
        val atts = listOf(Attachment(id = "a1", fileHash = "../evil"), Attachment(id = "a2", fileHash = "F".repeat(64)))
        val r = runner(api { requests++; respond("abc".toByteArray(), HttpStatusCode.OK) }, atts).run()
        assertEquals(FileSyncResult(0, 0, 0), r)
        assertEquals(0, requests)
    }

    @Test fun networkFailureKeepsRowAndIsRetryable() = runBlocking {
        val imported = files.importStream("abc".byteInputStream())
        db.pendingUploads().insert(PendingUploadEntity(imported.hash, "a.txt", "text/plain", 1))
        val r = runner(api { respond("boom", HttpStatusCode.InternalServerError) }).run()
        assertEquals(1, r.failed)
        assertTrue(r.retryable)
        assertEquals(1, db.pendingUploads().all().size)
    }

    @Test fun runDeletesOnlyStaleTempFiles() = runBlocking {
        val dir = tmp.newFolder("stale")
        files = AttachmentFiles(dir)
        val old = java.io.File(dir, "tmp-old").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L) }
        val oldPart = java.io.File(dir, "$abc.part").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - 2 * 3_600_000L) }
        val fresh = java.io.File(dir, "tmp-new").also { it.writeText("x") }
        runner(api { respond("{}", HttpStatusCode.OK, json) }).run()
        assertFalse(old.exists())
        assertFalse(oldPart.exists())
        assertTrue(fresh.exists())
    }
}
