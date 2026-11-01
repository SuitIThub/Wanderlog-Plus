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
