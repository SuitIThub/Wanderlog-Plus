package de.wanderlogplus.app.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoThumbnailsTest {
    @Test fun frameToJpegScalesLongestSideAndKeepsAspect() {
        val src = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        src.eraseColor(0xFF336699.toInt())
        val jpeg = frameToJpeg(src, maxSide = 1280, quality = 80)
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertEquals(1280, maxOf(decoded.width, decoded.height))
        assertEquals(640, minOf(decoded.width, decoded.height))
    }
}
