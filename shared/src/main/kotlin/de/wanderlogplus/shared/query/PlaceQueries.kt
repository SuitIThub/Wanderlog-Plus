package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*

enum class TagRank { STAR, NEUTRAL, WARNING }

data class Suggestion(val saved: SavedPlace, val place: Place?)

private const val DAY = 1440
private const val WEEK = 7 * DAY

private fun minutes(time: String): Int? {
    val p = time.split(':')
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}

/** Minute-of-week interval [start, end) of a period; end may exceed [WEEK] when it wraps. Null if unparsable. */
private fun interval(p: OpeningPeriod): Pair<Int, Int>? {
    val start = p.openDay * DAY + (minutes(p.openTime) ?: return null)
    val closeTime = p.closeTime ?: return start to start + WEEK // 24 h: no closing time
    var end = (p.closeDay ?: p.openDay) * DAY + (minutes(closeTime) ?: return null)
    if (end <= start) end += WEEK
    return start to end
}

private fun overlaps(p: OpeningPeriod, from: Int, to: Int): Boolean {
    val (s, e) = interval(p) ?: return false
    return (s < to && e > from) || (s < to + WEEK && e > from + WEEK)
}

/** True if open at some time on [dayOfWeek] (0 = Sunday); null if no periods are known. */
fun isOpenOn(periods: List<OpeningPeriod>, dayOfWeek: Int): Boolean? {
    if (periods.isEmpty()) return null
    return periods.any { overlaps(it, dayOfWeek * DAY, dayOfWeek * DAY + DAY) }
}

/** True if open at [time] ("HH:mm") on [dayOfWeek] (0 = Sunday), including periods past midnight; null if unknown. */
fun isOpenAt(periods: List<OpeningPeriod>, dayOfWeek: Int, time: String): Boolean? {
    if (periods.isEmpty()) return null
    val t = dayOfWeek * DAY + (minutes(time) ?: return null)
    return periods.any { overlaps(it, t, t + 1) }
}

fun tagRank(tags: List<String>): TagRank = when {
    tags.any { "⭐" in it || "🌟" in it } -> TagRank.STAR
    tags.any { "⚠" in it } -> TagRank.WARNING // also matches "⚠️" (with variation selector)
    else -> TagRank.NEUTRAL
}

/** STAR before NEUTRAL before WARNING, then rating descending (missing last), then name. */
fun sortSuggestions(list: List<Suggestion>): List<Suggestion> =
    list.sortedWith(
        compareBy<Suggestion> { tagRank(it.saved.tags).ordinal }
            .thenByDescending(nullsFirst()) { it.place?.rating }
            .thenBy { it.place?.name.orEmpty() },
    )

/** Places with unknown opening hours pass the weekday filter. */
fun filterSuggestions(list: List<Suggestion>, category: PlaceCategory?, openOnDayOfWeek: Int?): List<Suggestion> =
    list.filter { s ->
        (category == null || s.saved.category == category) &&
            (openOnDayOfWeek == null || isOpenOn(s.place?.openingPeriods.orEmpty(), openOnDayOfWeek) != false)
    }
