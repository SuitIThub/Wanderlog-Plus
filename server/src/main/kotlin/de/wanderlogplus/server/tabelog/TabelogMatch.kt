package de.wanderlogplus.server.tabelog

import de.wanderlogplus.server.ai.distanceKm
import de.wanderlogplus.shared.model.Place
import java.text.Normalizer

/** Which Tabelog hit is the saved restaurant: close by and with a similar name, else none. */
object TabelogMatch {
    /** How far the Tabelog entry may be from the saved place, in km. */
    const val MAX_KM = 0.3

    /** The nearest hit within [MAX_KM] whose name is [similar] to [jaName]; null when there is none. */
    fun pick(place: Place, jaName: String, hits: List<TabelogHit>): TabelogHit? =
        hits.mapNotNull { h ->
            val lat = h.lat ?: return@mapNotNull null
            val lng = h.lng ?: return@mapNotNull null
            val km = distanceKm(place.lat, place.lng, lat, lng)
            (h to km).takeIf { km <= MAX_KM && similar(jaName, h.name) }
        }.minByOrNull { it.second }?.first

    /**
     * Same restaurant name: one contains the other (width, case and spaces ignored), or the shop name before the
     * first space is the same ("一蘭 渋谷店" / "一蘭 渋谷スペイン坂店" – the distance then tells the branches apart).
     */
    internal fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // Containment only for names of two characters or more: "鮨" alone is in every sushi bar's name.
        if (minOf(x.length, y.length) >= 2 && (x in y || y in x)) return true
        val headA = norm(Normalizer.normalize(a, Normalizer.Form.NFKC).trim().substringBefore(' '))
        val headB = norm(Normalizer.normalize(b, Normalizer.Form.NFKC).trim().substringBefore(' '))
        return headA.length >= 2 && headA == headB
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filterNot { it.isWhitespace() }
}
