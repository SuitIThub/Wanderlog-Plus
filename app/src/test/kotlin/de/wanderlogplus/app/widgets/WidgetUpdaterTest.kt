package de.wanderlogplus.app.widgets

import de.wanderlogplus.app.testing.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WidgetUpdaterTest {
    private val now = LocalDateTime.parse("2027-04-02T23:30")
    private fun input() = WidgetInput(Fixtures.trip(), now, null, null, null, null)

    @Test fun updateSchedulesTheNextBoundary() = runBlocking {
        val at = mutableListOf<LocalDateTime>()
        var redrawn = 0
        val u = WidgetUpdater(load = { input() }, scheduler = { t, _ -> at += t }, redraw = { redrawn++ }, clock = { 0L })
        u.updateAll()
        assertEquals(1, redrawn)
        assertEquals(listOf(LocalDateTime.parse("2027-04-03T00:00")), at)
    }

    @Test fun inputIsCachedBriefly() = runBlocking {
        var loads = 0
        var t = 0L
        val u = WidgetUpdater(load = { loads++; input() }, scheduler = { _, _ -> }, redraw = {}, clock = { t })
        u.input(); u.input()
        assertEquals(1, loads)
        t = 2_000
        u.input()
        assertEquals(2, loads)
    }

    @Test fun aFailingLoadGivesAnEmptyInput() = runBlocking {
        val u = WidgetUpdater(load = { error("db") }, scheduler = { _, _ -> }, redraw = {}, clock = { 0L })
        assertNull(u.input().snapshot)
    }
}
