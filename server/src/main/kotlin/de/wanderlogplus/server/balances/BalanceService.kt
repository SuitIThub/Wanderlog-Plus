package de.wanderlogplus.server.balances

import de.wanderlogplus.shared.sync.BalanceLine
import de.wanderlogplus.shared.sync.BalanceReport
import de.wanderlogplus.shared.sync.BalanceStatus
import de.wanderlogplus.shared.sync.ServiceBalance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Credit and quotas of the paid services, asked live (OpenRouter, Apify, AirLabs) or as a link with own counts
 * (Parallel, Google). Read-only calls; keys never leave the server. Answers are kept for five minutes.
 */
class BalanceService(
    private val openRouterKey: String?,
    private val apifyToken: String?,
    private val airlabsKey: String?,
    private val googleUsage: GoogleUsage?,
    /** A Google key is configured (the card shows own counts then). */
    private val google: Boolean,
    /** Parallel is configured (its card only links to the dashboard). */
    private val parallel: Boolean,
    /** GET with headers → status and body; HTTP by default. */
    http: ((String, Map<String, String>) -> Pair<Int, String>)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long one service may take before its card says "nicht erreichbar". */
    private val timeoutMs: Long = 15_000,
) {
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val get: (String, Map<String, String>) -> Pair<Int, String> = http ?: ::httpGet
    private val json = Json { ignoreUnknownKeys = true }
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "balances").apply { isDaemon = true } }
    private var cached: BalanceReport? = null

    @Synchronized
    fun report(fresh: Boolean = false): BalanceReport {
        cached?.takeIf { !fresh && now() - it.fetchedAt < CACHE_MS }?.let { return it }
        // Each service on its own thread with its own deadline: a slow or failing one only greys out its card.
        val asks = listOf(
            Triple("openrouter", "OpenRouter (KI)", ::openRouter),
            Triple("apify", "Apify (Tabelog, ÖPNV)", ::apify),
            Triple("airlabs", "AirLabs (Flüge live)", ::airlabs),
        ).map { (id, name, f) ->
            val fallback = unavailable(id, name, null)
            CompletableFuture.supplyAsync({ f() }, pool)
                .completeOnTimeout(fallback, timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally { fallback }
        }
        val live = asks.map { it.join() }
        return BalanceReport(live + listOf(parallelCard(), googleCard()), now()).also { cached = it }
    }

    private fun openRouter(): ServiceBalance {
        val name = "OpenRouter (KI)"
        val key = openRouterKey ?: return ServiceBalance("openrouter", name, BalanceStatus.NOT_CONFIGURED)
        val auth = mapOf("Authorization" to "Bearer $key")
        val credits = data(get("https://openrouter.ai/api/v1/credits", auth)) ?: return unavailable("openrouter", name, "https://openrouter.ai/settings/credits")
        val total = credits.num("total_credits")
        val used = credits.num("total_usage")
        val keyInfo = data(get("https://openrouter.ai/api/v1/key", auth))
        val details = listOfNotNull(
            keyInfo?.num("usage_daily")?.let { BalanceLine("Heute", usd(it)) },
            keyInfo?.num("usage_weekly")?.let { BalanceLine("Diese Woche", usd(it)) },
            keyInfo?.num("usage_monthly")?.let { BalanceLine("Diesen Monat", usd(it)) },
        )
        return ServiceBalance(
            "openrouter", name, BalanceStatus.OK, "USD", used, total, if (total != null && used != null) total - used else null,
            details = details, link = "https://openrouter.ai/settings/credits", note = "Aufgeladenes Guthaben, verbraucht und übrig.",
        )
    }

    private fun apify(): ServiceBalance {
        val name = "Apify (Tabelog, ÖPNV)"
        val token = apifyToken ?: return ServiceBalance("apify", name, BalanceStatus.NOT_CONFIGURED)
        val d = data(get("https://api.apify.com/v2/users/me/limits", mapOf("Authorization" to "Bearer $token"))) ?: return unavailable("apify", name, "https://console.apify.com/billing")
        val limit = (d["limits"] as? JsonObject)?.num("maxMonthlyUsageUsd")
        val used = (d["current"] as? JsonObject)?.num("monthlyUsageUsd")
        val end = ((d["monthlyUsageCycle"] as? JsonObject)?.get("endAt") as? JsonPrimitive)?.content
        return ServiceBalance(
            "apify", name, BalanceStatus.OK, "USD", used, limit, if (limit != null && used != null) limit - used else null,
            periodEnd = end, link = "https://console.apify.com/billing", note = "Monatsbudget des Plans; danach starten keine Abrufe mehr.",
        )
    }

    private fun airlabs(): ServiceBalance {
        val name = "AirLabs (Flüge live)"
        val key = airlabsKey ?: return ServiceBalance("airlabs", name, BalanceStatus.NOT_CONFIGURED)
        // AirLabs sits behind Cloudflare, which turns away clients without a browser-like agent.
        val (status, body) = get("https://airlabs.co/api/v9/ping?api_key=" + URLEncoder.encode(key, "UTF-8"), mapOf("User-Agent" to "Mozilla/5.0 (Wanderlog Plus)"))
        val k = runCatching { ((json.parseToJsonElement(body).jsonObject["request"] as? JsonObject)?.get("key") as? JsonObject) }.getOrNull()
        if (status !in 200..299 || k == null) return unavailable("airlabs", name, "https://airlabs.co/account")
        val limit = k.num("limits_by_month")
        val remaining = k.num("limits_total")
        return ServiceBalance(
            "airlabs", name, BalanceStatus.OK, "CALLS", if (limit != null && remaining != null) (limit - remaining).coerceAtLeast(0.0) else null, limit, remaining,
            expiresAt = (k["expired"] as? JsonPrimitive)?.content,
            details = listOfNotNull((k["type"] as? JsonPrimitive)?.content?.let { BalanceLine("Plan", it) }),
            link = "https://airlabs.co/account", note = "Abfragen im Monat; läuft der Key ab, fehlt der Live-Status der Flüge.",
        )
    }

    private fun parallelCard() = if (!parallel) ServiceBalance("parallel", PARALLEL, BalanceStatus.NOT_CONFIGURED)
    else ServiceBalance("parallel", PARALLEL, BalanceStatus.LINK_ONLY, link = "https://platform.parallel.ai/", note = "Das Guthaben zeigt nur das Parallel-Dashboard.")

    private fun googleCard(): ServiceBalance {
        if (!google) return ServiceBalance("google", GOOGLE, BalanceStatus.NOT_CONFIGURED)
        val counts = googleUsage?.thisMonth().orEmpty()
        return ServiceBalance(
            "google", GOOGLE, BalanceStatus.LINK_ONLY, "CALLS", used = counts.values.sum().toDouble(),
            details = counts.entries.sortedByDescending { it.value }.map { BalanceLine(it.key, it.value.toString()) },
            link = "https://console.cloud.google.com/billing",
            note = "Google zeigt Kosten nur in der Cloud Console. Hier die Aufrufe des Servers in diesem Monat (ohne Karten und Aufrufe direkt aus der App).",
        )
    }

    private fun unavailable(service: String, name: String, link: String?) =
        ServiceBalance(service, name, BalanceStatus.UNAVAILABLE, link = link, note = "Gerade nicht abfragbar.")

    private fun data(answer: Pair<Int, String>): JsonObject? = if (answer.first !in 200..299) null
    else runCatching { json.parseToJsonElement(answer.second).jsonObject["data"] as? JsonObject }.getOrNull()

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun usd(v: Double) = String.format(Locale.GERMAN, "%.2f $", v)

    private fun httpGet(url: String, headers: Map<String, String>): Pair<Int, String> = try {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
        headers.forEach { (k, v) -> b.header(k, v) }
        client.send(b.build(), HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
    } catch (e: Exception) {
        0 to ""
    }

    private companion object {
        const val CACHE_MS = 5 * 60_000L
        const val PARALLEL = "Parallel (Recherche)"
        const val GOOGLE = "Google (Orte, Fotos, Routen)"
    }
}
