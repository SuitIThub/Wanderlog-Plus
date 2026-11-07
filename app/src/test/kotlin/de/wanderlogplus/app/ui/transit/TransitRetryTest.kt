package de.wanderlogplus.app.ui.transit

import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TransitPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitRetryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val ok = """{"fromStation":{"nameJa":"東京"},"toStation":{"nameJa":"中津川"},"datetime":"2027-05-20T08:00","routes":[{"departure":"08:00","arrival":"10:00","durationMinutes":120}]}"""

    private fun vm(answers: List<() -> Pair<HttpStatusCode, String>>): Pair<TransitViewModel, () -> Int> {
        var calls = 0
        val api = SyncApi(HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/transit/stations")) return@MockEngine respond("""{"stations":[]}""", HttpStatusCode.OK, json)
            val (status, body) = answers[minOf(calls++, answers.size - 1)]()
            respond(body, status, json)
        }), "https://s", "tok")
        val v = TransitViewModel({ api }, TransitPoint(35.68, 139.77, "Hotel"), TransitPoint(35.49, 137.5, "Ryokan"), "2027-05-20", "08:00", null, retryDelaysMillis = listOf(1, 1))
        return v to { calls }
    }

    private fun waitDone(v: TransitViewModel) {
        val end = System.currentTimeMillis() + 5_000
        while ((v.state.value.searching) && System.currentTimeMillis() < end) {
            // The main looper's clock only moves when told: let the retry pauses pass.
            org.robolectric.shadows.ShadowLooper.idleMainLooper(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
    }

    @Test
    fun retriesAfterNetworkError() {
        val (v, calls) = vm(listOf({ throw java.io.IOException("cut") }, { HttpStatusCode.OK to ok }))
        v.search()
        waitDone(v)
        assertNotNull(v.state.value.result)
        assertNull(v.state.value.message)
        assertEquals(2, calls())
    }

    @Test
    fun noRetryForOtherErrors() {
        val (v, calls) = vm(listOf({ HttpStatusCode.NotFound to """{"error":"no_route"}""" }))
        v.search()
        waitDone(v)
        assertEquals(1, calls())
        assertEquals("Keine Verbindung gefunden. Andere Zeit oder andere Bahnhöfe versuchen.", v.state.value.message)
    }

    @Test
    fun givesUpAfterThreeNetworkErrors() {
        val (v, calls) = vm(listOf({ throw java.io.IOException("cut") }))
        v.search()
        waitDone(v)
        assertEquals(3, calls())
        assertEquals("Server nicht erreichbar.", v.state.value.message)
    }
}
