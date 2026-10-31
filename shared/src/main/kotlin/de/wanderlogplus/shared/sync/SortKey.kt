package de.wanderlogplus.shared.sync

/** Fractional sort keys over the alphabet 0-9A-Za-z (ASCII order). Keys never end in '0'. */
object SortKey {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62

    private fun digit(c: Char): Int {
        val d = ALPHABET.indexOf(c)
        require(d >= 0) { "Invalid sort key character: $c" }
        return d
    }

    /** Returns a key strictly between [before] and [after]; null means an open end. */
    fun between(before: String?, after: String?): String {
        require(before == null || after == null || before < after) { "before must be < after" }
        val sb = StringBuilder()
        var bounded = after != null
        var i = 0
        while (true) {
            val lo = if (before != null && i < before.length) digit(before[i]) else 0
            val hi = if (bounded) {
                require(i < after!!.length) { "No key exists between '$before' and '$after'" }
                digit(after[i])
            } else BASE
            if (hi - lo > 1) {
                sb.append(ALPHABET[(lo + hi) / 2])
                return sb.toString()
            }
            sb.append(ALPHABET[lo])
            if (hi > lo) bounded = false
            i++
        }
    }

    /** [count] ascending, evenly spread keys. */
    fun sequence(count: Int): List<String> {
        require(count >= 0)
        if (count == 0) return emptyList()
        var len = 1
        var space = BASE.toLong()
        while (space < count + 1) { space *= BASE; len++ }
        len++; space *= BASE
        val step = space / (count + 1)
        return (1..count).map { i ->
            var v = step * i
            val chars = CharArray(len)
            for (p in len - 1 downTo 0) { chars[p] = ALPHABET[(v % BASE).toInt()]; v /= BASE }
            String(chars).trimEnd('0')
        }
    }
}
