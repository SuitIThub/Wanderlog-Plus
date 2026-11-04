package de.wanderlogplus.app.vault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.VaultFoundEntity
import de.wanderlogplus.client.SyncApi
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

    private fun scanner(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): VaultScanner =
        VaultScanner(
            appContext = ApplicationProvider.getApplicationContext(),
            dao = db.vault(),
            api = { SyncApi(HttpClient(MockEngine(handler)), "https://s.example", "tok") },
            openStream = { "abc".byteInputStream() },
        )

    private fun seed(uri: String = "content://media/1") =
        db.vault().insertFound(VaultFoundEntity(uri, "a.jpg", "image/jpeg", 0.9, foundAt = 1, uploadedAt = null))

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
}
