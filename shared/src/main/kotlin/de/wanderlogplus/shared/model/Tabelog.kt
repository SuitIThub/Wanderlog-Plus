package de.wanderlogplus.shared.model

import kotlinx.serialization.Serializable

/** FOUND: matched; NOT_FOUND: searched without a safe match (not retried by itself); REQUESTED: the app asks for a (re)load. */
enum class TabelogStatus { FOUND, NOT_FOUND, REQUESTED }

/** What Tabelog says about a restaurant, fetched by the server. */
@Serializable
data class TabelogInfo(
    val status: TabelogStatus,
    /** The Japanese page (https://tabelog.com/kyoto/A2601/…/26032368/); for REQUESTED optionally one set by hand. */
    val url: String = "",
    /** The name on Tabelog (Japanese). */
    val name: String = "",
    /** 0–5. */
    val score: Double? = null,
    val reviewCount: Int? = null,
    val genre: String = "",
    val budgetLunch: String = "",
    val budgetDinner: String = "",
    val hours: String = "",
    val reservation: String = "",
    val station: String = "",
    /** Two or three sentences in German from the newest reviews. */
    val summary: String = "",
    /** Epoch millis of the last fetch. */
    val checkedAt: Long = 0,
)

/** The English page of the same restaurant (`/en` after the host); empty without a url. */
fun TabelogInfo.englishUrl(): String {
    val u = url.trim()
    if (u.isEmpty()) return ""
    val host = Regex("""^https://(s\.)?tabelog\.com""").find(u)?.value ?: return u
    val rest = u.removePrefix(host)
    return if (rest.startsWith("/en/")) u else "https://tabelog.com/en$rest"
}

/** Only real Tabelog pages: https on tabelog.com or s.tabelog.com. */
fun isTabelogUrl(u: String): Boolean = Regex("""^https://(s\.)?tabelog\.com/\S*$""").matches(u.trim())

/** A saved restaurant (food or nightlife, not dropped) with a position: the places Tabelog is fetched for. */
fun isRestaurant(saved: SavedPlace, place: Place?): Boolean =
    place != null && (saved.category == PlaceCategory.FOOD || saved.category == PlaceCategory.NIGHTLIFE) &&
        saved.status != PlaceStatus.DROPPED && !(place.lat == 0.0 && place.lng == 0.0)
