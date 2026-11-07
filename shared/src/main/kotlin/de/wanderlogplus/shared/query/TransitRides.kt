package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitStation
import java.time.LocalDate
import java.time.LocalTime

/** A timetable connection as rides: one train per ride (walks left out), for saving it as a journey. */
object TransitRides {
    private val carriers = listOf(
        "JR", "Tokyo Metro", "Toei", "Ōsaka Metro", "Kyōto U-Bahn", "Nagoya U-Bahn", "Kintetsu", "Keihan", "Hankyū", "Hankyu",
        "Hanshin", "Meitetsu", "Odakyū", "Keiō", "Seibu", "Tōbu", "Keisei", "Nankai",
    )

    /** The operator from a readable line name ("JR Shinkansen Nozomi 15" → "JR"); null when not known. */
    fun carrierOf(line: String): String? {
        val l = line.trim()
        return carriers.firstOrNull { l == it || l.startsWith("$it ") }?.let { if (it == "Hankyu") "Hankyū" else it }
    }

    private fun time(s: String): LocalTime? = try { LocalTime.parse(s.trim().take(5)) } catch (e: Exception) { null }

    /**
     * The rides of the first route as trains (id empty; the caller assigns it): line as number, operator, stations with
     * coordinates, date and time; a time earlier than the one before means the next day. Each train's [Train.transit]
     * holds only its ride; the whole fare stays on the first one.
     */
    fun ridesOf(result: TransitResult): List<Train> {
        val route = result.routes.firstOrNull() ?: return emptyList()
        val rides = route.legs.filter { !it.walk }
        if (rides.isEmpty()) return emptyList()
        var date = try { LocalDate.parse(route.date.ifBlank { result.datetime.take(10) }) } catch (e: Exception) { LocalDate.now() }
        var last: LocalTime? = null
        fun dated(t: String): Pair<String, String?> {
            val lt = time(t) ?: return date.toString() to null
            if (last != null && lt < last) date = date.plusDays(1)
            last = lt
            return date.toString() to lt.toString()
        }
        fun ep(st: TransitStation, at: Pair<String, String?>) = Endpoint(name = st.name.ifBlank { st.nameJa }, lat = st.lat, lng = st.lng, date = at.first, time = at.second)
        return rides.mapIndexed { i, leg ->
            val dep = dated(leg.departure)
            val arr = dated(leg.arrival)
            val minutes = run {
                val a = time(leg.departure); val b = time(leg.arrival)
                if (a == null || b == null) 0 else ((b.toSecondOfDay() - a.toSecondOfDay()) / 60).let { if (it < 0) it + 24 * 60 else it }
            }
            val first = i == 0
            val name = leg.line.ifBlank { leg.lineJa }
            Train(
                id = "",
                carrier = carrierOf(name),
                number = name,
                depart = ep(leg.from, dep),
                arrive = ep(leg.to, arr),
                transit = result.copy(
                    fromStation = leg.from, toStation = leg.to,
                    routes = listOf(
                        route.copy(
                            departure = leg.departure, arrival = leg.arrival, durationMinutes = minutes, ridingMinutes = minutes, transfers = 0,
                            fareYen = if (first) route.fareYen else 0, baseFareYen = if (first) route.baseFareYen else 0,
                            expressFareYen = if (first) route.expressFareYen else 0, fees = if (first) route.fees else emptyList(),
                            legs = listOf(leg), date = dep.first,
                        ),
                    ),
                ),
            )
        }
    }
}
