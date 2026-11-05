package de.wanderlogplus.app.vault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.VaultVideoRequestEntity
import de.wanderlogplus.client.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After every successful sync the app pulls the web overview's trigger: resume-flag, sticky pause,
 * and the video refs to upload. Pause wins; refs are mirrored locally so a paused device still
 * knows what to upload once resumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultUploadTriggerTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun trigger(
        body: String = """{"requested":false}""",
        clearFails: Boolean = false,
        onRequested: () -> Unit = {},
    ): Pair<VaultUploadTrigger, MutableList<String>> {
        val calls = mutableListOf<String>()
        val api = SyncApi(HttpClient(MockEngine { req ->
            calls += "${req.method.value} ${req.url.encodedPath}"
            when {
                req.method == HttpMethod.Get -> respond(body, HttpStatusCode.OK, jsonHeaders)
                clearFails -> respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }), "https://s.example", "tok")
        return VaultUploadTrigger(api = { api }, dao = db.vault(), onRequested = onRequested) to calls
    }

    @Test fun setFlagSchedulesAndClears() = runTest {
        var scheduled = 0
        val (t, calls) = trigger("""{"requested":true}""") { scheduled++ }
        t.check()
        assertEquals(1, scheduled)
        assertEquals(listOf("GET /api/vault/uploads/request", "POST /api/vault/uploads/request/clear"), calls)
    }

    @Test fun unsetFlagDoesNothing() = runTest {
        var scheduled = 0
        val (t, calls) = trigger("""{"requested":false}""") { scheduled++ }
        t.check()
        assertEquals(0, scheduled)
        assertEquals(listOf("GET /api/vault/uploads/request"), calls)
    }

    @Test fun noServerConfiguredIsIgnored() = runTest {
        var scheduled = 0
        VaultUploadTrigger(api = { null }, dao = db.vault(), onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
    }

    @Test fun unreachableServerKeepsFlagForNextSync() = runTest {
        val api = SyncApi(HttpClient(MockEngine { throw IOException("offline") }), "https://s.example", "tok")
        var scheduled = 0
        VaultUploadTrigger(api = { api }, dao = db.vault(), onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
    }

    @Test fun oldServerWithoutEndpointIsIgnored() = runTest {
        val api = SyncApi(HttpClient(MockEngine { respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, jsonHeaders) }), "https://s.example", "tok")
        var scheduled = 0
        VaultUploadTrigger(api = { api }, dao = db.vault(), onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
    }

    @Test fun schedulingHappensEvenWhenClearFails() = runTest {
        var scheduled = 0
        val (t, _) = trigger("""{"requested":true}""", clearFails = true) { scheduled++ }
        t.check()
        assertEquals(1, scheduled)
    }

    @Test fun pausedMirrorsRefsButDoesNotScheduleOrClear() = runTest {
        var scheduled = 0
        val (t, calls) = trigger("""{"requested":true,"paused":true,"videoRefs":["content://v/1"]}""") { scheduled++ }
        t.check()
        assertEquals(0, scheduled)
        assertEquals(listOf("content://v/1"), db.vault().videoRequestRefs())
        assertEquals(listOf("GET /api/vault/uploads/request"), calls)
    }

    @Test fun refsOnlySchedulesWithoutClearing() = runTest {
        var scheduled = 0
        val (t, calls) = trigger("""{"requested":false,"videoRefs":["content://v/1"]}""") { scheduled++ }
        t.check()
        assertEquals(1, scheduled)
        assertEquals(listOf("content://v/1"), db.vault().videoRequestRefs())
        assertEquals(listOf("GET /api/vault/uploads/request"), calls)
    }

    @Test fun staleLocalRefsArePrunedToServerList() = runTest {
        db.vault().insertVideoRequest(VaultVideoRequestEntity("content://old", 1))
        db.vault().insertVideoRequest(VaultVideoRequestEntity("content://keep", 1))
        val (t, _) = trigger("""{"requested":false,"videoRefs":["content://keep","content://new"]}""")
        t.check()
        assertEquals(setOf("content://keep", "content://new"), db.vault().videoRequestRefs().toSet())
    }

    @Test fun legacyRequestedBodyStillWorks() = runTest {
        var scheduled = 0
        val (t, _) = trigger("""{"requested":true}""") { scheduled++ }
        t.check()
        assertEquals(1, scheduled)
    }
}
