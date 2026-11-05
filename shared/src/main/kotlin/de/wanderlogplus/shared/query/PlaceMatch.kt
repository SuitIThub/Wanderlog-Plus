package de.wanderlogplus.shared.query

import java.text.Normalizer
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether two place names mean the same place: equal after normalizing (case, accents, punctuation, words like
 * "juku", "temple", "station"), e.g. "Magome" and "Magome-juku Post Town", "Ryokan Tsutamura" and "Tsutamura".
 * Used before a found place is added, so an already known place is not created a second time.
 */
object PlaceMatch {
    private val FILLER = setOf(
        "the", "der", "die", "das", "juku", "post", "town", "temple", "shrine", "station", "tempel", "schrein",
        "bahnhof", "jinja", "dera", "ji", "ryokan", "hotel", "inn", "guesthouse", "japan", "prefecture",
    )

    /** Lowercase words without accents and punctuation, filler words dropped (kept when nothing else is left). */
    fun words(name: String): List<String> {
        val plain = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val all = plain.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return all.filter { it !in FILLER }.ifEmpty { all }
    }

    fun sameName(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // Words in any order ("Tsumago Juku" / "Juku Tsumago"); containment would match "Gion" with "Gion Onsen".
        return x.sorted() == y.sorted() || x.joinToString("") == y.joinToString("")
    }

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /** Same place: very close (< [SAME_KM]), or the same name within [NAME_KM] (a village or a big site). */
    fun same(nameA: String, latA: Double?, lngA: Double?, nameB: String, latB: Double?, lngB: Double?): Boolean {
        val km = if (latA != null && lngA != null && latB != null && lngB != null && !(latB == 0.0 && lngB == 0.0)) distanceKm(latA, lngA, latB, lngB) else null
        if (km != null && km < SAME_KM) return true
        return sameName(nameA, nameB) && (km == null || km < NAME_KM)
    }

    const val SAME_KM = 0.15
    const val NAME_KM = 3.0
}
