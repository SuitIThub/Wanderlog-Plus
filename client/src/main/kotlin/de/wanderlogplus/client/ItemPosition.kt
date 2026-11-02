package de.wanderlogplus.client

/** Where a new program item goes: first, last, or right after an existing item. */
sealed interface ItemPosition {
    data object Start : ItemPosition
    data object End : ItemPosition
    data class After(val itemId: String) : ItemPosition

    /** Compact form for navigation arguments: "start", "end" or "after:<id>". */
    fun encode(): String = when (this) {
        Start -> "start"
        End -> "end"
        is After -> "after:$itemId"
    }

    companion object {
        /** Inverse of [encode]; anything unknown means [End]. */
        fun decode(raw: String?): ItemPosition = when {
            raw == "start" -> Start
            raw != null && raw.startsWith("after:") && raw.length > 6 -> After(raw.substring(6))
            else -> End
        }
    }
}
