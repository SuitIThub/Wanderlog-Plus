package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Endpoint
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How long a train or flight takes, across days and time zones. Times are stored as local times of their endpoint; the
 * zone comes from the endpoint ([Endpoint.timeZone]), else from the airport code ([AIRPORT_ZONES]), else – for trains
 * and buses in Japan (or without position) – Japanese time. Real zones ("Europe/Berlin") also handle summer time.
 */
object RideTimes {
    val JAPAN: ZoneId = ZoneId.of("Asia/Tokyo")

    /** Time zones of airports by IATA code: Japan, Germany and frequent stopovers. */
    val AIRPORT_ZONES: Map<String, String> = buildMap {
        listOf("HND", "NRT", "KIX", "ITM", "NGO", "HIJ", "FUK", "CTS", "OKA", "HKD", "SDJ", "KOJ", "UKB", "KMQ", "TOY", "MMB", "AOJ", "KMJ", "OIT", "MYJ", "TAK", "KCZ", "OKJ", "NGS", "KMI", "ISG")
            .forEach { put(it, "Asia/Tokyo") }
        listOf("FRA", "MUC", "BER", "DUS", "HAM", "STR", "CGN", "LEJ", "NUE", "HAJ").forEach { put(it, "Europe/Berlin") }
        put("ZRH", "Europe/Zurich"); put("GVA", "Europe/Zurich"); put("VIE", "Europe/Vienna"); put("AMS", "Europe/Amsterdam")
        put("CDG", "Europe/Paris"); put("LHR", "Europe/London"); put("CPH", "Europe/Copenhagen"); put("HEL", "Europe/Helsinki")
        put("WAW", "Europe/Warsaw"); put("IST", "Europe/Istanbul"); put("DOH", "Asia/Qatar"); put("DXB", "Asia/Dubai")
        put("AUH", "Asia/Dubai"); put("ICN", "Asia/Seoul"); put("GMP", "Asia/Seoul"); put("PVG", "Asia/Shanghai")
        put("PEK", "Asia/Shanghai"); put("PKX", "Asia/Shanghai"); put("HKG", "Asia/Hong_Kong"); put("SIN", "Asia/Singapore")
        put("TPE", "Asia/Taipei"); put("BKK", "Asia/Bangkok"); put("MNL", "Asia/Manila")
    }

    /** Zones to pick from by hand, with a readable name. */
    val ZONE_OPTIONS: List<Pair<String, String>> = listOf(
        "Europe/Berlin" to "Deutschland", "Asia/Tokyo" to "Japan", "Europe/Zurich" to "Schweiz", "Europe/Vienna" to "Österreich",
        "Europe/Amsterdam" to "Niederlande", "Europe/Paris" to "Frankreich", "Europe/London" to "Großbritannien",
        "Europe/Copenhagen" to "Dänemark", "Europe/Helsinki" to "Finnland", "Europe/Warsaw" to "Polen", "Europe/Istanbul" to "Türkei",
        "Asia/Qatar" to "Katar", "Asia/Dubai" to "Vereinigte Arabische Emirate", "Asia/Seoul" to "Südkorea", "Asia/Shanghai" to "China",
        "Asia/Hong_Kong" to "Hongkong", "Asia/Taipei" to "Taiwan", "Asia/Singapore" to "Singapur", "Asia/Bangkok" to "Thailand",
        "Asia/Manila" to "Philippinen", "America/New_York" to "USA (Ostküste)", "America/Los_Angeles" to "USA (Westküste)",
        "UTC" to "UTC",
    )

    /** The readable name of a zone id; the id itself when it is not in [ZONE_OPTIONS]. */
    fun zoneLabel(id: String): String = ZONE_OPTIONS.firstOrNull { it.first == id }?.second ?: id

    private fun parse(id: String?): ZoneId? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun inJapan(e: Endpoint): Boolean {
        val lat = e.lat ?: return true
        val lng = e.lng ?: return true
        return lat in 20.0..46.0 && lng in 122.0..154.0
    }

    /** The zone of [e]: its own, else its airport's, else (not for flights) Japanese time in Japan; null when unknown. */
    fun zoneOf(e: Endpoint, flight: Boolean): ZoneId? =
        parse(e.timeZone)
            ?: e.code?.trim()?.uppercase(Locale.ROOT)?.let { parse(AIRPORT_ZONES[it]) }
            ?: JAPAN.takeIf { !flight && inJapan(e) }

    private fun time(t: String?): LocalTime? {
        val p = t?.trim()?.takeIf { it.length >= 4 }?.split(':') ?: return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.take(2)?.toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
    }

    private fun date(d: String?): LocalDate? = d?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun ends(m: Movement) = when (m) {
        is Movement.FlightMove -> m.flight.depart to m.flight.arrive
        is Movement.TrainMove -> m.train.depart to m.train.arrive
    }

    /** Departure and arrival as zoned times; an arrival without date on the departure day (or the next, when earlier). */
    fun zoned(m: Movement): Pair<ZonedDateTime, ZonedDateTime>? {
        val flight = m is Movement.FlightMove
        val (dep, arr) = ends(m)
        val depZone = zoneOf(dep, flight) ?: return null
        val arrZone = zoneOf(arr, flight) ?: return null
        val depDay = date(dep.date) ?: return null
        val departs = ZonedDateTime.of(depDay, time(dep.time) ?: return null, depZone)
        val arrTime = time(arr.time) ?: return null
        var arrives = ZonedDateTime.of(date(arr.date) ?: depDay, arrTime, arrZone)
        if (arr.date == null && arrives.isBefore(departs)) arrives = arrives.plusDays(1)
        return departs to arrives
    }

    /** Minutes the ride takes; null when a time, a date or a zone is unknown. */
    fun minutes(m: Movement): Long? = zoned(m)?.let { (d, a) -> ChronoUnit.MINUTES.between(d, a).takeIf { it >= 0 } }
}
