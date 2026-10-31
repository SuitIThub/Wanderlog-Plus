package de.wanderlogplus.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun `receive from far-future remote keeps later local ticks greater`() {
        val clock = HlcClock("a") { 1000L }
        val remote = Hlc(9_999_999_000L, 3, "b")
        val r = clock.receive(remote)
        assertTrue(r > remote); assertTrue(clock.tick() > r)
    }

    @Test fun `encode sorts like compareTo and roundtrips`() {
        val a = Hlc(999, 5, "x"); val b = Hlc(1000, 0, "a")
        assertTrue(a.encode() < b.encode())
        assertEquals(a, Hlc.parse(a.encode()))
    }

    @Test fun `nodeId breaks ties`() {
        assertTrue(Hlc(1, 1, "a") < Hlc(1, 1, "b"))
    }
}
