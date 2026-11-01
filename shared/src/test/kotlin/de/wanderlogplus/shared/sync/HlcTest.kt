package de.wanderlogplus.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import java.util.Locale
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HlcTest {
    @Test fun `tick is strictly monotonic even if wall clock stands still`() {
        val clock = HlcClock("a") { 1000L }
        val t1 = clock.tick(); val t2 = clock.tick()
        assertTrue(t2 > t1); assertEquals(1000L, t2.wallMillis); assertEquals(1, t2.counter)
    }

    @Test fun `tick never goes backwards when wall clock jumps back`() {
        var now = 5000L; val clock = HlcClock("a") { now }
        val t1 = clock.tick(); now = 1000L
        assertTrue(clock.tick() > t1)
    }

    @Test fun `receive from remote within drift keeps later local ticks greater`() {
        val clock = HlcClock("a") { 1000L }
        val remote = Hlc(5_000_000L, 3, "b")
        val r = clock.receive(remote)
        assertTrue(r > remote); assertTrue(clock.tick() > r)
    }

    @Test fun `encode sorts like compareTo and roundtrips`() {
        val a = Hlc(999, 5, "x"); val b = Hlc(1000, 0, "a")
        assertTrue(a.encode() < b.encode())
        assertEquals(a, Hlc.parse(a.encode()))
    }

    @Test fun `encode is locale independent and ASCII`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val h = Hlc(1_700_000_000_123, 42, "node")
            val s = h.encode()
            assertTrue(s.all { it.code < 128 }, s)
            assertEquals(h, Hlc.parse(s))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test fun `out-of-range values are rejected`() {
        assertFailsWith<IllegalArgumentException> { Hlc(-1, 0, "a") }
        assertFailsWith<IllegalArgumentException> { Hlc(0, -1, "a") }
        assertFailsWith<IllegalArgumentException> { Hlc(0, 1_000_000, "a") }
        Hlc(0, 999_999, "a")
    }

    @Test fun `parse rejects invalid strings`() {
        assertFailsWith<IllegalArgumentException> { Hlc.parse("garbage") }
        assertFailsWith<IllegalArgumentException> { Hlc.parse("1-2-a") }
    }

    @Test fun `nodeId containing dash roundtrips`() {
        val h = Hlc(5, 7, "dev-a-1")
        assertEquals(h, Hlc.parse(h.encode()))
    }

    @Test fun `nodeId breaks ties`() {
        assertTrue(Hlc(1, 1, "a") < Hlc(1, 1, "b"))
    }

    @Test fun receiveCapsFarFutureRemote() {
        val clock = HlcClock("a") { 1_000L }
        val r = clock.receive(Hlc(1_000L + 3 * 86_400_000L, 3, "b"))
        assertEquals(1_000L + 86_400_000L, r.wallMillis)
        assertTrue(clock.tick() > r)
    }

    @Test fun receiveWithinDriftUnchanged() {
        val clock = HlcClock("a") { 1_000L }
        val r = clock.receive(Hlc(1_000L + 3_600_000L, 5, "b"))
        assertEquals(1_000L + 3_600_000L, r.wallMillis); assertEquals(6, r.counter)
    }
}
