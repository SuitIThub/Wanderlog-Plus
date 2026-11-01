package de.wanderlogplus.client

/** Google's encoded polyline format (precision 5), as returned by the Routes API. */
object Polyline {
    /** Decodes to (lat, lng) pairs; a malformed tail is ignored (the points decoded so far are kept). */
    fun decode(encoded: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        var lat = 0
        var lng = 0
        fun next(): Int? {
            var result = 0
            var shift = 0
            while (true) {
                if (i >= encoded.length) return null
                val b = encoded[i++].code - 63
                if (b < 0) return null
                result = result or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) break
                if (shift > 30) return null
            }
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        while (i < encoded.length) {
            val dLat = next() ?: break
            val dLng = next() ?: break
            lat += dLat
            lng += dLng
            out += lat / 1e5 to lng / 1e5
        }
        return out
    }
}
