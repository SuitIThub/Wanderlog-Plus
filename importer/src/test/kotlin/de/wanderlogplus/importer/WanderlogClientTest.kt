package de.wanderlogplus.importer

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WanderlogClientTest {
    private class FakeResponse(private val code: Int, private val body: String) : HttpResponse<String> {
        override fun statusCode() = code
        override fun body() = body
        override fun request(): HttpRequest = HttpRequest.newBuilder(URI.create("https://example.invalid")).build()
        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
        override fun uri(): URI = URI.create("https://example.invalid")
        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    /** Each step is an [HttpResponse] or a [Throwable] to throw. */
    private class Script(private vararg val steps: Any) {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val sleep: (Long) -> Unit = { sleeps += it }
        @Suppress("UNCHECKED_CAST")
        val send: (HttpRequest) -> HttpResponse<String> = {
            val step = steps[calls++]
            if (step is Throwable) throw step
            step as HttpResponse<String>
        }
    }

    @Test fun retriesOnceOn5xx() {
        val s = Script(FakeResponse(503, ""), FakeResponse(200, "{}"))
        assertEquals("{}", WanderlogClient(s.send, s.sleep).fetch("k"))
        assertEquals(2, s.calls)
        assertEquals(listOf(1000L), s.sleeps)
    }

    @Test fun retriesOnceOnIOException() {
        val s = Script(IOException("boom"), FakeResponse(200, "{}"))
        assertEquals("{}", WanderlogClient(s.send, s.sleep).fetch("k"))
        assertEquals(2, s.calls)
        assertEquals(listOf(1000L), s.sleeps)
    }

    @Test fun failsAfterSecond5xx() {
        val s = Script(FakeResponse(500, ""), FakeResponse(502, ""))
        val e = assertFailsWith<ImportFormatException> { WanderlogClient(s.send, s.sleep).fetch("k") }
        assertTrue("HTTP 502" in e.message!!)
        assertEquals(2, s.calls)
    }

    @Test fun doesNotRetry4xx() {
        val s = Script(FakeResponse(404, ""))
        val e = assertFailsWith<ImportFormatException> { WanderlogClient(s.send, s.sleep).fetch("k") }
        assertTrue("HTTP 404" in e.message!!)
        assertEquals(1, s.calls)
    }
}
