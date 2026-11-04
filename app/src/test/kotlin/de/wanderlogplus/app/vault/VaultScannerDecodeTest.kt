package de.wanderlogplus.app.vault

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Runs the real BitmapFactory decode path of [VaultScanner.scoreStream] on generated PNGs.
 * Regression test: an inJustDecodeBounds decode returns null by design and must not abort scoring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultScannerDecodeTest {

    private fun png(argb: Int, w: Int = 400, h: Int = 300): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun skinColoredPhotoScoresHigh() {
        val score = VaultScanner.scoreStream { ByteArrayInputStream(png(0xFFE0AC69.toInt())) }
        assertNotNull(score)
        assertTrue("score was $score", score!! > 0.9)
    }

    @Test
    fun bluePhotoScoresLow() {
        val score = VaultScanner.scoreStream { ByteArrayInputStream(png(0xFF2E6BD8.toInt())) }
        assertNotNull(score)
        assertTrue("score was $score", score!! < 0.05)
    }

    @Test
    fun nullStreamScoresNull() {
        assertNull(VaultScanner.scoreStream { null })
    }

    @Test
    fun tinyImageScoresNull() {
        assertNull(VaultScanner.scoreStream { ByteArrayInputStream(png(0xFFE0AC69.toInt(), w = 32, h = 32)) })
    }
}
