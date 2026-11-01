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

/** "2027-05-20" -> "Do 20.05." (trip list cards); unparsable input is returned unchanged. */
fun dayCardLabel(date: String): String = dayLabel(date).replaceFirst(", ", " ")

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

fun categoryLabel(c: de.wanderlogplus.shared.model.PlaceCategory?) = when (c) {
    null -> "Alle"
    de.wanderlogplus.shared.model.PlaceCategory.SIGHT -> "Sehenswürdigkeiten"
    de.wanderlogplus.shared.model.PlaceCategory.FOOD -> "Essen"
    de.wanderlogplus.shared.model.PlaceCategory.SHOPPING -> "Shopping"
    de.wanderlogplus.shared.model.PlaceCategory.NIGHTLIFE -> "Nightlife"
    de.wanderlogplus.shared.model.PlaceCategory.OTHER -> "Sonstiges"
}

fun statusLabel(s: de.wanderlogplus.shared.model.PlaceStatus) = when (s) {
    de.wanderlogplus.shared.model.PlaceStatus.IDEA -> "Idee"
    de.wanderlogplus.shared.model.PlaceStatus.PLANNED -> "Geplant"
    de.wanderlogplus.shared.model.PlaceStatus.VISITED -> "Besucht"
    de.wanderlogplus.shared.model.PlaceStatus.DROPPED -> "Verworfen"
}

/**
 * Opening hours per weekday, Monday to Sunday: "HH:mm–HH:mm" per period (comma-separated), "24 Stunden geöffnet" for a
 * period without closing time, "geschlossen" when periods exist but none opens that day, "unbekannt" without periods.
 */
fun openingHoursLines(periods: List<de.wanderlogplus.shared.model.OpeningPeriod>): List<Pair<String, String>> =
    WEEKDAYS_DE.mapIndexed { i, name ->
        val dow = (i + 1) % 7 // OpeningPeriod: 0 = Sunday
        val text = when {
            periods.isEmpty() -> "unbekannt"
            else -> periods.filter { it.openDay == dow }.sortedBy { it.openTime }.map {
                if (it.closeTime == null) "24 Stunden geöffnet" else "${it.openTime}–${it.closeTime}"
            }.joinToString(", ").ifEmpty { "geschlossen" }
        }
        name to text
    }

/** "★ 4,5" (German decimal comma). */
fun ratingLabel(rating: Double): String = "★ " + String.format(java.util.Locale.GERMAN, "%.1f", rating)

private val MONTHS_DE = listOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember",
)

/**
 * Hero kicker "Do · 20. Mai · Tag 13"; the day number counts from [tripStart] (day 1) and is left out when that is
 * missing or unparsable. Unparsable [date] is returned unchanged.
 */
fun dayKicker(date: String, tripStart: String?): String {
    val d = try { java.time.LocalDate.parse(date) } catch (_: java.time.format.DateTimeParseException) { return date }
    val start = try { tripStart?.let { java.time.LocalDate.parse(it) } } catch (_: java.time.format.DateTimeParseException) { null }
    val head = "${WEEKDAYS_DE[d.dayOfWeek.value - 1]} · ${d.dayOfMonth}. ${MONTHS_DE[d.monthValue - 1]}"
    return if (start == null) head else "$head · Tag ${java.time.temporal.ChronoUnit.DAYS.between(start, d) + 1}"
}

/** Small symbol for photo placeholders by category. */
fun categorySymbol(c: de.wanderlogplus.shared.model.PlaceCategory?) = when (c) {
    de.wanderlogplus.shared.model.PlaceCategory.SIGHT -> "⛩"
    de.wanderlogplus.shared.model.PlaceCategory.FOOD -> "🍜"
    de.wanderlogplus.shared.model.PlaceCategory.SHOPPING -> "🛍"
    de.wanderlogplus.shared.model.PlaceCategory.NIGHTLIFE -> "🍸"
    de.wanderlogplus.shared.model.PlaceCategory.OTHER, null -> "📍"
}
