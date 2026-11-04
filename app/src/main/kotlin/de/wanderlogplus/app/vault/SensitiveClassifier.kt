package de.wanderlogplus.app.vault

/**
 * On-device scorer for images: 0.0 (no sensitive content) to 1.0 (very likely). Pure Kotlin on decoded pixels,
 * so it is unit-testable without Android. A TensorFlow-Lite model can replace the heuristic behind this interface.
 */
interface SensitiveClassifier {
    fun score(pixels: IntArray, width: Int, height: Int): Double

    /** [score] at or above which an image counts as a find (recall-oriented: rather too many than too few). */
    fun flagged(score: Double): Boolean
}

/**
 * Skin-tone ratio heuristic: the classic Kovac/Swift RGB rules (daylight, flashlight) plus a YCbCr rule,
 * which also covers darker skin tones the RGB rules miss. Crude but dependency-free; false positives
 * (beaches, faces, sand) are expected and accepted.
 */
class SkinRatioClassifier(private val threshold: Double = DEFAULT_THRESHOLD) : SensitiveClassifier {

    override fun score(pixels: IntArray, width: Int, height: Int): Double {
        if (pixels.isEmpty()) return 0.0
        var skin = 0
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (isSkin(r, g, b)) skin++
        }
        return skin.toDouble() / pixels.size
    }

    override fun flagged(score: Double): Boolean = score >= threshold

    companion object {
        /** Low bar on purpose: misses are worse than false positives here. */
        const val DEFAULT_THRESHOLD = 0.25

        fun isSkin(r: Int, g: Int, b: Int): Boolean {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            // Rule 1: uniform daylight.
            val daylight = r > 95 && g > 40 && b > 20 && (max - min) > 15 &&
                kotlin.math.abs(r - g) > 15 && r > g && r > b
            // Rule 2: flashlight.
            val flash = r > 220 && g > 210 && b > 170 &&
                kotlin.math.abs(r - g) <= 15 && r > b && g > b
            if (daylight || flash) return true
            // Rule 3: YCbCr chrominance – catches darker skin and mixed lighting the RGB rules let through.
            val cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
            return cb in 77.0..127.0 && cr in 133.0..173.0
        }
    }
}
