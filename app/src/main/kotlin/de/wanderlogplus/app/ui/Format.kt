package de.wanderlogplus.app.ui

/** German relative time, e.g. "vor 3 Min.". */
fun relativeAgo(millis: Long, now: Long): String {
    val minutes = (now - millis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "gerade eben"
        minutes < 60 -> "vor $minutes Min."
        minutes < 24 * 60 -> "vor ${minutes / 60} Std."
        minutes < 2 * 24 * 60 -> "vor 1 Tag"
        else -> "vor ${minutes / (24 * 60)} Tagen"
    }
}

private val WEEKDAYS_DE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** "2027-05-09" -> "So, 09.05."; unparsable input is returned unchanged. */
fun dayLabel(date: String): String {
    val d = try { java.time.LocalDate.parse(date) } catch (_: java.time.format.DateTimeParseException) { return date }
    return "%s, %02d.%02d.".format(java.util.Locale.GERMAN, WEEKDAYS_DE[d.dayOfWeek.value - 1], d.dayOfMonth, d.monthValue)
}

private val HEX_COLOR = Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")
private val NEUTRAL_REGION_COLOR = androidx.compose.ui.graphics.Color(0xFF9E9E9E)

/** Parses "#rrggbb" (or "#aarrggbb"); null or malformed values give a neutral grey. */
fun regionColor(hex: String?): androidx.compose.ui.graphics.Color {
    val h = hex?.trim()?.removePrefix("#") ?: return NEUTRAL_REGION_COLOR
    if (!HEX_COLOR.matches(h)) return NEUTRAL_REGION_COLOR
    val v = h.toLong(16)
    return androidx.compose.ui.graphics.Color(if (h.length == 6) 0xFF000000L or v else v)
}
