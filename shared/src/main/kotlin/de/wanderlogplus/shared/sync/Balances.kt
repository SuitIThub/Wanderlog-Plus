package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/** OK: numbers below; NOT_CONFIGURED: no key on the server; UNAVAILABLE: the service did not answer; LINK_ONLY: only a link (and own counts). */
enum class BalanceStatus { OK, NOT_CONFIGURED, UNAVAILABLE, LINK_ONLY }

/** One line of extra figures ("Diesen Monat" → "14,63 $"). */
@Serializable
data class BalanceLine(val label: String, val value: String)

/** Credit or quota of one paid service the server uses. [unit] is "USD" or "CALLS"; dates are ISO instants. */
@Serializable
data class ServiceBalance(
    /** openrouter, apify, airlabs, parallel, google. */
    val service: String,
    val name: String,
    val status: BalanceStatus,
    val unit: String = "",
    val used: Double? = null,
    val limit: Double? = null,
    val remaining: Double? = null,
    /** End of the current billing/usage period. */
    val periodEnd: String? = null,
    /** When the key or plan runs out. */
    val expiresAt: String? = null,
    val details: List<BalanceLine> = emptyList(),
    /** Where to look it up by hand (dashboard). */
    val link: String? = null,
    /** Short German note (what the numbers mean, why there are none). */
    val note: String = "",
)

/** `GET /api/balances`. */
@Serializable
data class BalanceReport(val services: List<ServiceBalance>, val fetchedAt: Long)
