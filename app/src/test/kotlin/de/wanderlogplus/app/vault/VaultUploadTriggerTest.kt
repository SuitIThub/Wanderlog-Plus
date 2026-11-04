package de.wanderlogplus.app.vault

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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The server cannot push to the device; after a successful sync the app checks the flag that the web overview's
 * "Upload fortsetzen" button sets. At-least-once: the flag is cleared only after the upload pass was scheduled.
 */
class VaultUploadTriggerTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun setFlagSchedulesAndClears() = runTest {
        val calls = mutableListOf<String>()
        val api = SyncApi(HttpClient(MockEngine { req ->
            calls += "${req.method.value} ${req.url.encodedPath}"
            if (req.method == HttpMethod.Get) respond("""{"requested":true}""", HttpStatusCode.OK, jsonHeaders)
            else respond("", HttpStatusCode.NoContent)
        }), "https://s.example", "tok")
        var scheduled = 0
        VaultUploadTrigger(api = { api }, onRequested = { scheduled++ }).check()
        assertEquals(1, scheduled)
        assertEquals(listOf("GET /api/vault/uploads/request", "POST /api/vault/uploads/request/clear"), calls)
    }

    @Test fun unsetFlagDoesNothing() = runTest {
        val calls = mutableListOf<String>()
        val api = SyncApi(HttpClient(MockEngine { req ->
            calls += "${req.method.value} ${req.url.encodedPath}"
            respond("""{"requested":false}""", HttpStatusCode.OK, jsonHeaders)
        }), "https://s.example", "tok")
        var scheduled = 0
        VaultUploadTrigger(api = { api }, onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
        assertEquals(listOf("GET /api/vault/uploads/request"), calls)
    }

    @Test fun noServerConfiguredIsIgnored() = runTest {
        var scheduled = 0
        VaultUploadTrigger(api = { null }, onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
    }

    @Test fun unreachableServerKeepsFlagForNextSync() = runTest {
        val api = SyncApi(HttpClient(MockEngine { throw IOException("offline") }), "https://s.example", "tok")
        var scheduled = 0
        // No exception escapes: a failing sync must not fail because of the trigger check.
        VaultUploadTrigger(api = { api }, onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
    }

    @Test fun oldServerWithoutEndpointIsIgnored() = runTest {
        val api = SyncApi(HttpClient(MockEngine { respond("""{"error":"not_found"}""", HttpStatusCode.NotFound, jsonHeaders) }), "https://s.example", "tok")
        var scheduled = 0
        VaultUploadTrigger(api = { api }, onRequested = { scheduled++ }).check()
        assertEquals(0, scheduled)
    }

    @Test fun schedulingHappensEvenWhenClearFails() = runTest {
        val api = SyncApi(HttpClient(MockEngine { req ->
            if (req.method == HttpMethod.Get) respond("""{"requested":true}""", HttpStatusCode.OK, jsonHeaders)
            else respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError, jsonHeaders)
        }), "https://s.example", "tok")
        var scheduled = 0
        VaultUploadTrigger(api = { api }, onRequested = { scheduled++ }).check()
        assertEquals(1, scheduled)
    }
}
