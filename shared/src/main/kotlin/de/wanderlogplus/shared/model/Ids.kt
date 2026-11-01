package de.wanderlogplus.shared.model

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

object Ids {
    private val secure = SecureRandom()

    /** UUID v7 (RFC 9562): 48-bit unix millis, then random bits; sorts by creation time as a string. */
    fun newId(now: Long = System.currentTimeMillis(), random: Random = secure): String {
        val msb = (now shl 16) or 0x7000L or random.nextInt(0x1000).toLong()
        val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }

    /** Deterministic ID of the app-created day for [date] in [tripId]. */
    fun dayId(tripId: String, date: String): String =
        UUID.nameUUIDFromBytes("day:$tripId:$date".toByteArray(Charsets.UTF_8)).toString()
}
