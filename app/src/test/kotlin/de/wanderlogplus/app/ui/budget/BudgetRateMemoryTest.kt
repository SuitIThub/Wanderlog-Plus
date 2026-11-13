package de.wanderlogplus.app.ui.budget

import de.wanderlogplus.client.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A fetched yen rate is handed on, so the budget widget knows it offline. */
class BudgetRateMemoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun aFetchedRateIsRemembered() = runBlocking {
        val seen = mutableListOf<Pair<String, Double>>()
        val api = SyncApi(HttpClient(MockEngine { respond("""{"from":"JPY","to":"EUR","rate":0.0061,"date":"2027-04-02"}""", HttpStatusCode.OK, json) }), "https://s.example", "tok")
        val vm = BudgetViewModel({ _ -> }, CoroutineScope(Dispatchers.Unconfined), { api }, { "2027-04-02" }, onRate = { c, r -> seen += c to r.rate })
        vm.rate("JPY", null)
        assertEquals(listOf("JPY" to 0.0061), seen)
    }
}
