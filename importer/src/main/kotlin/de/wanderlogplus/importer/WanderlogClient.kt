package de.wanderlogplus.importer

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class WanderlogClient(
    private val send: (HttpRequest) -> HttpResponse<String> = defaultSend(),
) {
    /** GETs the public trip plan JSON; one retry on I/O failure or HTTP >= 500, other non-200 -> [ImportFormatException]. */
    fun fetch(tripKey: String): String {
        val request = HttpRequest.newBuilder(URI.create("https://wanderlog.com/api/tripPlans/$tripKey?clientSchemaVersion=2&registerView=false"))
            .header("User-Agent", "Mozilla/5.0")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        var response: HttpResponse<String>? = null
        var failure: IOException? = null
        for (attempt in 1..2) {
            response = null
            try {
                response = send(request)
                if (response.statusCode() < 500) break
            } catch (e: IOException) {
                failure = e
            }
        }
        if (response == null) throw ImportFormatException("Request failed: ${failure?.message}", failure)
        if (response.statusCode() != 200) throw ImportFormatException("HTTP ${response.statusCode()}")
        return response.body()
    }

    private companion object {
        fun defaultSend(): (HttpRequest) -> HttpResponse<String> {
            val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            return { http.send(it, HttpResponse.BodyHandlers.ofString()) }
        }
    }
}
