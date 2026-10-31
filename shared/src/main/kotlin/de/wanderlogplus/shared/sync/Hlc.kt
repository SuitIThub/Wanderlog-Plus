package de.wanderlogplus.shared.sync

import java.util.Locale

/** Hybrid Logical Clock timestamp, ordered by (wallMillis, counter, nodeId). */
data class Hlc(val wallMillis: Long, val counter: Int, val nodeId: String) : Comparable<Hlc> {
    init {
        require(wallMillis >= 0 && counter in 0..999_999) { "HLC out of range: wallMillis=$wallMillis counter=$counter" }
    }

    override fun compareTo(other: Hlc): Int {
        val w = wallMillis.compareTo(other.wallMillis)
        if (w != 0) return w
        val c = counter.compareTo(other.counter)
        if (c != 0) return c
        return nodeId.compareTo(other.nodeId)
    }

    companion object
}

/** Lexicographically sortable form, equal in order to [Hlc.compareTo]. */
fun Hlc.encode(): String = String.format(Locale.ROOT, "%015d-%06d-%s", wallMillis, counter, nodeId)

private val HLC_FORMAT = Regex("""(\d{15})-(\d{6})-(.+)""")

fun Hlc.Companion.parse(s: String): Hlc {
    val m = HLC_FORMAT.matchEntire(s) ?: throw IllegalArgumentException("Invalid HLC: '$s'")
    val (wall, counter, node) = m.destructured
    return Hlc(
        wall.toLongOrNull() ?: throw IllegalArgumentException("Invalid HLC wall time: '$s'"),
        counter.toIntOrNull() ?: throw IllegalArgumentException("Invalid HLC counter: '$s'"),
        node,
    )
}

/** Not thread-safe by itself; methods are synchronized. */
class HlcClock(val nodeId: String, private val now: () -> Long = System::currentTimeMillis) {
    private var lastWall = Long.MIN_VALUE
    private var lastCounter = 0

    @Synchronized
    fun tick(): Hlc {
        val wall = maxOf(now(), lastWall)
        lastCounter = if (wall == lastWall) lastCounter + 1 else 0
        lastWall = wall
        return Hlc(wall, lastCounter, nodeId)
    }

    @Synchronized
    fun receive(remote: Hlc): Hlc {
        val wall = maxOf(now(), lastWall, remote.wallMillis)
        lastCounter = when {
            wall == lastWall && wall == remote.wallMillis -> maxOf(lastCounter, remote.counter) + 1
            wall == lastWall -> lastCounter + 1
            wall == remote.wallMillis -> remote.counter + 1
            else -> 0
        }
        lastWall = wall
        return Hlc(wall, lastCounter, nodeId)
    }
}
