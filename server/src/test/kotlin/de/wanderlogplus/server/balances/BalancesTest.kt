package de.wanderlogplus.server.balances

import de.wanderlogplus.shared.sync.BalanceStatus
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import de.wanderlogplus.server.authed
import de.wanderlogplus.server.module
import java.nio.file.Files
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalancesTest {
    private val dir = Files.createTempDirectory("wlp-balances")

    @Test
    fun `google usage counts per month and survives a restart`() {
        var month = YearMonth.of(2026, 11)
        val file = dir.resolve("api-usage.json")
        val u = GoogleUsage(file) { month }
        u.count("Details"); u.count("Details"); u.count("Fotos")
        assertEquals(mapOf("Details" to 2, "Fotos" to 1), u.thisMonth())
        assertEquals(mapOf("Details" to 2, "Fotos" to 1), GoogleUsage(file) { month }.thisMonth())
        month = YearMonth.of(2026, 12)
        assertEquals(emptyMap(), u.thisMonth())
        u.count("Routen")
        assertEquals(mapOf("Routen" to 1), GoogleUsage(file) { month }.thisMonth())
    }

    @Test
    fun `a broken usage file starts empty`() {
        val file = dir.resolve("broken.json")
        Files.writeString(file, "{kaputt")
        val u = GoogleUsage(file) { YearMonth.of(2026, 11) }
        assertEquals(emptyMap(), u.thisMonth())
        u.count("Details")
        assertEquals(1, u.thisMonth()["Details"])
    }

    // Shapes of the real answers (2026-11-11), values made up.
    private val openRouterCredits = """{"data":{"total_credits":50,"total_usage":34.993574054}}"""
    private val openRouterKey = """{"data":{"label":"sk-or-v1-x","limit":null,"usage":14.63,"usage_daily":0.41,"usage_weekly":0.5,"usage_monthly":14.63}}"""
    private val apifyLimits = """{"data":{"monthlyUsageCycle":{"startAt":"2026-11-04T00:00:00.000Z","endAt":"2026-12-03T23:59:59.999Z"},
        "limits":{"maxMonthlyUsageUsd":5},"current":{"monthlyUsageUsd":1.0823861254733065}}}"""
    private val airlabsPing = """{"request":{"key":{"type":"free","expired":"2026-12-05T00:00:00.000Z","limits_by_month":1000,"limits_total":999}}}"""

    private fun service(
        answers: Map<String, Pair<Int, String>>,
        openRouter: String? = "or", apify: String? = "ap", airlabs: String? = "al",
        usage: GoogleUsage? = null, google: Boolean = true,
    ): Pair<BalanceService, MutableList<Pair<String, Map<String, String>>>> {
        val asked = mutableListOf<Pair<String, Map<String, String>>>()
        val s = BalanceService(openRouter, apify, airlabs, usage, google, parallel = true, http = { url, headers ->
            asked += url to headers
            answers.entries.firstOrNull { url.contains(it.key) }?.value ?: (404 to "")
        })
        return s to asked
    }

    private val all = mapOf(
        "openrouter.ai/api/v1/credits" to (200 to openRouterCredits),
        "openrouter.ai/api/v1/key" to (200 to openRouterKey),
        "api.apify.com/v2/users/me/limits" to (200 to apifyLimits),
        "airlabs.co/api/v9/ping" to (200 to airlabsPing),
    )

    @Test
    fun `openrouter shows what is left and this month's usage`() {
        val (s, asked) = service(all)
        val or = s.report().services.single { it.service == "openrouter" }
        assertEquals(BalanceStatus.OK, or.status)
        assertEquals(50.0, or.limit)
        assertEquals(34.993574054, or.used!!, 1e-9)
        assertEquals(15.006425946, or.remaining!!, 1e-9)
        assertEquals("USD", or.unit)
        assertTrue(or.details.any { it.label == "Diesen Monat" && it.value == "14,63 $" })
        assertTrue(asked.filter { "openrouter" in it.first }.all { it.second["Authorization"] == "Bearer or" })
    }

    @Test
    fun `apify shows the monthly plan usage and the cycle end`() {
        val ap = service(all).first.report().services.single { it.service == "apify" }
        assertEquals(5.0, ap.limit)
        assertEquals(1.0823861254733065, ap.used!!, 1e-9)
        assertEquals("2026-12-03T23:59:59.999Z", ap.periodEnd)
    }

    @Test
    fun `airlabs shows calls and when the key expires and sends a browser agent`() {
        val (s, asked) = service(all)
        val al = s.report().services.single { it.service == "airlabs" }
        assertEquals("CALLS", al.unit)
        assertEquals(1000.0, al.limit)
        assertEquals(999.0, al.remaining)
        assertEquals("2026-12-05T00:00:00.000Z", al.expiresAt)
        assertTrue(asked.single { "airlabs" in it.first }.second["User-Agent"]!!.startsWith("Mozilla/5.0"))
    }

    @Test
    fun `missing keys errors parallel and google`() {
        val usage = GoogleUsage(dir.resolve("u.json")) { YearMonth.of(2026, 11) }.also { it.count("Textsuche") }
        val (s, _) = service(mapOf("api.apify.com" to (402 to "{}")), openRouter = null, airlabs = null, usage = usage)
        val r = s.report().services.associateBy { it.service }
        assertEquals(BalanceStatus.NOT_CONFIGURED, r.getValue("openrouter").status)
        assertEquals(BalanceStatus.NOT_CONFIGURED, r.getValue("airlabs").status)
        assertEquals(BalanceStatus.UNAVAILABLE, r.getValue("apify").status)
        assertNull(r.getValue("apify").used)
        assertEquals(BalanceStatus.LINK_ONLY, r.getValue("parallel").status)
        assertTrue(r.getValue("parallel").link!!.startsWith("https://platform.parallel.ai"))
        val g = r.getValue("google")
        assertEquals(BalanceStatus.LINK_ONLY, g.status)
        assertTrue(g.details.any { it.label == "Textsuche" && it.value == "1" })
        assertTrue(g.link!!.startsWith("https://console.cloud.google.com"))
    }

    @Test
    fun `answers are cached for five minutes unless fresh`() {
        var t = 0L
        val asked = mutableListOf<String>()
        val s = BalanceService("or", null, null, null, google = false, parallel = false, http = { url, _ -> asked += url; all.entries.first { url.contains(it.key) }.value }, now = { t })
        s.report(); s.report()
        assertEquals(2, asked.size)
        t += 6 * 60_000
        s.report()
        assertEquals(4, asked.size)
        s.report(fresh = true)
        assertEquals(6, asked.size)
    }

    @Test
    fun `counting wrappers count by kind and pass answers through`() {
        val u = GoogleUsage(null) { YearMonth.of(2026, 11) }
        val ok = 200 to "{}"
        assertEquals(ok, counted(u, de.wanderlogplus.server.ai.PlacesSearchTransport { ok }).searchText("x"))
        counted(u, de.wanderlogplus.server.PlaceDetailsTransport { ok }).details("id")
        val api = counted(u, de.wanderlogplus.server.PlacesApi { _, _, _ -> ok })
        api.post("places:searchNearby", "", ""); api.post("places:searchText", "", "")
        val photos = counted(u, object : de.wanderlogplus.server.PlacesPhotoTransport {
            override fun details(googlePlaceId: String) = ok
            override fun media(name: String, maxWidth: Int) = 200 to ByteArray(0)
        })
        photos.details("id"); photos.media("n", 100)
        counted(u, de.wanderlogplus.server.RoutesTransport { ok }).post("")
        val ja = counted(u, object : de.wanderlogplus.server.tabelog.GoogleJaTransport {
            override fun details(id: String) = ok
            override fun search(body: String) = ok
        })
        ja.details("id"); ja.search("")
        assertEquals(mapOf("Textsuche" to 3, "Details" to 3, "Umgebungssuche" to 1, "Fotos" to 1, "Routen" to 1), u.thisMonth())
    }

    @Test
    fun `the route answers without keys with cards that say so`() = io.ktor.server.testing.testApplication {
        val base = de.wanderlogplus.server.testServices(dir)
        application { module(base) }
        val r = client.get("/api/balances") { authed() }
        assertEquals(io.ktor.http.HttpStatusCode.OK, r.status)
        val report = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(de.wanderlogplus.shared.sync.BalanceReport.serializer(), r.bodyAsText())
        assertEquals(listOf("openrouter", "apify", "airlabs", "parallel", "google"), report.services.map { it.service })
        assertTrue(report.services.all { it.status == BalanceStatus.NOT_CONFIGURED })
    }
}
