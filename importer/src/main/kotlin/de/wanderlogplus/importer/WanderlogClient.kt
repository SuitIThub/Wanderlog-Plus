package de.wanderlogplus.importer

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class WanderlogClient(private val http: HttpClient = HttpClient.newHttpClient()) {
    /** GETs the public trip plan JSON; one retry on I/O failure, HTTP != 200 -> [ImportFormatException]. */
    fun fetch(tripKey: String): String {
        val request = HttpRequest.newBuilder(URI.create("https://wanderlog.com/api/tripPlans/$tripKey?clientSchemaVersion=2&registerView=false"))
            .header("User-Agent", "Mozilla/5.0")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e2: IOException) {
                throw ImportFormatException("Request failed: ${e2.message}", e2)
            }
        }
        if (response.statusCode() != 200) throw ImportFormatException("HTTP ${response.statusCode()}")
        return response.body()
    }
}
