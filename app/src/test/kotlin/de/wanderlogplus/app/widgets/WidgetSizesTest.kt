package de.wanderlogplus.app.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizesTest {
    private fun b(w: Int, h: Int) = bucketOf(DpSize(w.dp, h.dp))

    @Test fun bucketsPickTheLargestThatFits() {
        assertEquals(Bucket.TINY, b(57, 57))
        assertEquals(Bucket.STRIP, b(200, 60))
        assertEquals(Bucket.SMALL, b(120, 120))
        assertEquals(Bucket.MEDIUM, b(190, 120))
        assertEquals(Bucket.WIDE, b(260, 120))
        assertEquals(Bucket.TALL, b(190, 260))
        assertEquals(Bucket.LARGE, b(300, 300))
        assertEquals(Bucket.TINY, b(40, 40))
        assertEquals(ALL_SIZES.size, Bucket.entries.size)
    }
}
