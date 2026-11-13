package de.wanderlogplus.app.widgets

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.TripWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class WidgetDataTest {
    private val trip = Fixtures.trip()
    private fun at(s: String) = LocalDateTime.parse(s)
    private fun input(now: String, snapshot: de.wanderlogplus.client.TripSnapshot? = trip, weather: TripWeather? = null, rate: StoredRate? = null, quick: QuickResult? = null) =
        WidgetInput(snapshot, at(now), weather, rate, lastChatTitle = null, quick = quick)
    private fun build(now: String, snapshot: de.wanderlogplus.client.TripSnapshot? = trip) = WidgetData.build(input(now, snapshot))

    @Test fun duringTheTripTheNextStepsComeWithTimeAndCountdown() {
        val m = build("2027-04-02T08:00")
        assertEquals(Phase.DURING, m.phase)
        val first = m.nextUp.upcoming.first()
        assertEquals("09:00", first.time)
        assertEquals("Alter Bergtempel", first.title)
        assertEquals(60L, first.inMinutes)
        assertEquals(WidgetTarget.Day("2027-04-02"), first.target)
        assertEquals(listOf("Alter Bergtempel", "Nudelküche Weizenhalm"), m.nextUp.upcoming.map { it.title })
    }

    @Test fun aRunningStepIsCurrentNotUpcoming() {
        val m = build("2027-04-02T09:30")
        assertEquals("Alter Bergtempel", m.nextUp.current?.title)
        assertEquals(listOf("Nudelküche Weizenhalm"), m.nextUp.upcoming.map { it.title })
    }

    @Test fun phasesBeforeAfterAndWithoutTrip() {
        val before = build("2027-03-01T10:00")
        assertEquals(Phase.BEFORE, before.phase)
        assertTrue(before.nextUp.daysUntilStart!! > 0)
        assertEquals("Testreise", before.nextUp.tripName)
        assertEquals(Phase.AFTER, build("2027-05-01T10:00").phase)
        assertEquals(Phase.NO_TRIP, build("2027-04-02T10:00", snapshot = null).phase)
    }

    @Test fun theRideWithin36Hours() {
        val r = build("2027-04-04T08:00").ride
        assertNotNull(r)
        assertEquals("10:12", r!!.departs)
        assertEquals(132L, r.inMinutes)
        assertEquals(WidgetTarget.Logistics(de.wanderlogplus.shared.sync.EntityType.TRAIN, "tr-1"), r.target)
        assertNull(build("2027-04-02T10:00").ride) // 48 h before
    }

    @Test fun stayOfTheNightAndCheckOutDay() {
        val night = build("2027-04-02T10:00").stay!!
        assertEquals("Gasthaus am Hang", night.name)
        assertEquals("TEST-0001", night.confirmation)
        val out = build("2027-04-04T08:00").stay!!
        assertEquals("Gasthaus am Hang", out.name)
        assertTrue(out.checkLine, out.checkLine.startsWith("Check-out heute"))
        assertEquals("Hotel Brandung", build("2027-04-04T15:00").stay!!.name)
    }

    @Test fun rateFromStoreWinsThenFromExpenseThenNone() {
        val withExpense = trip.copy(
            expenses = listOf(
                Expense("e-1", amount = 1200.0, currency = "JPY", date = "2027-04-02", rate = 0.0062, rateDate = "2027-04-02"),
                Expense("e-2", amount = 800.0, currency = "JPY", date = "2027-04-01", rate = 0.0060),
                Expense("e-3", amount = 20.0, currency = "EUR", date = "2027-04-02"),
            ),
        )
        val stored = StoredRate(0.0061, "2027-04-02")
        assertEquals(stored, WidgetData.build(input("2027-04-02T10:00", withExpense, rate = stored)).budget.rate)
        assertEquals(StoredRate(0.0062, "2027-04-02"), WidgetData.build(input("2027-04-02T10:00", withExpense)).budget.rate)
        assertNull(build("2027-04-02T10:00").budget.rate)
        val b = WidgetData.build(input("2027-04-02T10:00", withExpense, rate = stored)).budget
        assertEquals(1200L, b.todayJpy)
        assertEquals(1000L to 6.1, b.table[1])
        assertEquals(listOf(500L, 1000L, 5000L, 10000L), b.table.map { it.first })
    }

    @Test fun weatherOfTheDay() {
        val w = TripWeather(listOf(DayWeather("2027-04-02", place = "Bergstadt", tempMax = 18.0), DayWeather("2027-04-03", tempMax = 15.0)))
        val m = WidgetData.build(input("2027-04-02T10:00", weather = w)).weather!!
        assertEquals(18.0, m.today?.tempMax)
        assertEquals(15.0, m.tomorrow?.tempMax)
        assertEquals("Bergstadt", m.place)
        assertNull(build("2027-04-02T10:00").weather)
    }

    @Test fun quickResultsAgeAndExpire() {
        val now = at("2027-04-02T15:00")
        fun q(hoursAgo: Long, date: String = "2027-04-02") = QuickResult(
            "food", "c-1", QuickResult.DONE, now.minusHours(hoursAgo).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), date,
        )
        assertEquals(false, WidgetData.build(input("2027-04-02T15:00", quick = q(1))).chat.stale)
        val stale = WidgetData.build(input("2027-04-02T15:00", quick = q(3))).chat
        assertTrue(stale.stale)
        assertNotNull(stale.quick)
        assertNull(WidgetData.build(input("2027-04-02T15:00", quick = q(7))).chat.quick)
        assertNull(WidgetData.build(input("2027-04-02T15:00", quick = q(1, date = "2027-04-01"))).chat.quick)
    }

    @Test fun nextBoundary() {
        // Before the temple (09:00, its way from the stay comes first) the next change is its start.
        val b = WidgetData.nextBoundary(input("2027-04-02T08:00"))
        assertTrue(b.toString(), b > at("2027-04-02T08:00") && b <= at("2027-04-02T09:00"))
        // Late evening without anything left: midnight.
        assertEquals(at("2027-04-03T00:00"), WidgetData.nextBoundary(input("2027-04-02T23:30")))
    }
}
