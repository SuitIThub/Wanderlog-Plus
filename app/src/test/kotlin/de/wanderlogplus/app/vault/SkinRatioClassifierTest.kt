package de.wanderlogplus.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinRatioClassifierTest {
    private val classifier = SkinRatioClassifier()

    private fun fill(color: Int, count: Int) = IntArray(count) { color }

    @Test
    fun skinTonesScoreOneAndAreFlagged() {
        // Typical skin tones (daylight rule and flash rule).
        val daylight = fill(0xE0AC69, 500)
        assertEquals(1.0, classifier.score(daylight, 25, 20), 0.001)
        val flash = fill(0xEAD3C0, 500)
        assertEquals(1.0, classifier.score(flash, 25, 20), 0.001)
        // Darker skin is only caught by the YCbCr rule.
        val dark = fill(0x503728, 500)
        assertEquals(1.0, classifier.score(dark, 25, 20), 0.001)
        assertTrue(classifier.flagged(classifier.score(daylight, 25, 20)))
    }

    @Test
    fun landscapesScoreZeroAndAreNotFlagged() {
        val pixels = fill(0x2E6BD8, 250) + fill(0x3FA34D, 250) // sky + grass
        val score = classifier.score(pixels, 25, 20)
        assertEquals(0.0, score, 0.001)
        assertFalse(classifier.flagged(score))
    }

    @Test
    fun thresholdIsRecallOriented() {
        // 40% skin beats the default 0.30 bar; 10% does not.
        val forty = fill(0xE0AC69, 200) + fill(0x202020, 300)
        assertTrue(classifier.flagged(classifier.score(forty, 25, 20)))
        val ten = fill(0xE0AC69, 50) + fill(0x202020, 450)
        assertFalse(classifier.flagged(classifier.score(ten, 25, 20)))
    }

    @Test
    fun emptyInputScoresZero() {
        assertEquals(0.0, classifier.score(IntArray(0), 0, 0), 0.0)
    }
}
