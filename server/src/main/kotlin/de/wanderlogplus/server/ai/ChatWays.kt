package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.sync.RouteInfo
import de.wanderlogplus.shared.sync.RouteRequest
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import java.util.Locale

/** Timetable connections and routes for the chat: the server's TransitService and RoutesService (fakes in tests). */
interface ChatWays {
    /** Throws the transit service's errors (not configured, unavailable, no route, no station). */
    fun transit(req: TransitRequest): TransitResult

    /** Throws the routes service's errors (not configured, unavailable, no route). */
    fun route(req: RouteRequest): RouteInfo
}

/** German prompt text for connections and routes. */
object ChatWaysText {
    fun transit(r: TransitResult, fromName: String, toName: String): String = buildString {
        append("Verbindungen ").append(fromName).append(" → ").append(toName)
        append(if (r.searchType == "arrive") ", Ankunft bis " else ", Abfahrt ab ").append(r.datetime.replace('T', ' ')).append(":\n")
        append("Bahnhöfe: ").append(station(r.fromStation.name, r.fromStation.nameJa)).append(" → ").append(station(r.toStation.name, r.toStation.nameJa))
        val walks = listOfNotNull(
            r.walkToStationMeters?.let { "zum Bahnhof ca. ${meters(it)} Luftlinie" },
            r.walkFromStationMeters?.let { "vom Zielbahnhof ca. ${meters(it)}" },
        )
        if (walks.isNotEmpty()) append(" (").append(walks.joinToString(", ")).append(")")
        append('\n')
        r.stationWarning?.let { append("Achtung: ").append(it).append('\n') }
        r.routes.take(3).forEachIndexed { i, route -> append(i + 1).append(") ").append(route(route)).append('\n') }
    }

    private fun route(r: TransitRoute): String = buildString {
        append(r.departure).append("–").append(r.arrival).append(", ").append(r.durationMinutes).append(" Min.")
        append(", ").append(if (r.transfers == 0) "ohne Umstieg" else "${r.transfers} Umstieg" + if (r.transfers > 1) "e" else "")
        if (r.fareYen > 0) append(", ").append(String.format(Locale.GERMAN, "%,d ¥", r.fareYen))
        val labels = listOfNotNull("schnellste".takeIf { r.fastest }, "günstigste".takeIf { r.cheapest }, "wenigste Umstiege".takeIf { r.fewestTransfers })
        if (labels.isNotEmpty()) append(" [").append(labels.joinToString(", ")).append("]")
        val legs = r.legs.filter { !it.walk }
        if (legs.isNotEmpty()) {
            append(": ")
            append(legs.joinToString(" | ") { l ->
                val line = l.line.ifBlank { l.lineJa }
                val head = l.headsign.ifBlank { l.headsignJa }.takeIf { it.isNotBlank() }?.let { " Richtung $it" }.orEmpty()
                val platform = l.departurePlatform?.let { " Gleis $it" }.orEmpty()
                "$line$head ${station(l.from.name, l.from.nameJa)} ${l.departure}$platform → ${station(l.to.name, l.to.nameJa)} ${l.arrival}"
            })
        }
    }

    fun route(info: RouteInfo, mode: String): String =
        "$mode: ${minutes(info.durationSeconds)}, ${distanceText(info.distanceMeters / 1000.0)}"

    private fun station(name: String, ja: String) = if (name.isBlank() || name == ja) ja else "$name ($ja)"
    private fun meters(m: Int) = if (m < 1000) "$m m" else String.format(Locale.GERMAN, "%.1f km", m / 1000.0)
    fun minutes(seconds: Long): String {
        val m = ((seconds + 59) / 60).toInt()
        return if (m < 60) "$m Min." else "${m / 60} Std. ${m % 60} Min."
    }
}
