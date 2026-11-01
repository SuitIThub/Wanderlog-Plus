package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TodayQueriesTest {
    private val trip = Trip("trip", startDate = "2027-05-08", endDate = "2027-05-29")
    private val stays = listOf(
        Stay("s1", regionId = "t", checkIn = "2027-05-09", checkOut = "2027-05-13", cancellationDeadline = "2027-05-05T18:00"),
        Stay("s2", regionId = "t", checkIn = "2027-05-13", checkOut = "2027-05-20", cancellationDeadline = "2027-04-20"),
        Stay("s3", regionId = "t", checkIn = "2027-05-20", checkOut = "2027-05-29", cancellationDeadline = "2027-05-02"),
        Stay("s4", regionId = "t", checkIn = "2027-05-20", checkOut = "2027-05-29"),
    )
    private val flights = listOf(Flight("f", depart = Endpoint(date = "2027-05-10", time = "14:00")))
    private val trains = listOf(
        Train("t1", depart = Endpoint(date = "2027-05-10", time = "08:30")),
        Train("t2", depart = Endpoint(date = "2027-05-10")),
        Train("t3", depart = Endpoint(date = "2027-05-11", time = "08:30")),
    )

    private fun q(t: Trip = trip) =
        TodayQueries(TripQueries(t, stays, emptyList(), emptyList(), emptyList(), emptyList()), t, stays, flights, trains)

    @Test fun before() {
        val s = assertIs<TodayState.Before>(q().state(LocalDate.parse("2027-05-01")))
        assertEquals(7, s.daysUntilStart)
        assertEquals(listOf("s3", "s1"), s.deadlines.map { it.stay.id })
        assertEquals(listOf("2027-05-02", "2027-05-05"), s.deadlines.map { it.date })
    }

    @Test fun during() {
        val s = assertIs<TodayState.During>(q().state(LocalDate.parse("2027-05-10")))
        assertEquals("2027-05-10", s.day.date)
        assertEquals(listOf("t1", "f", "t2"), s.movements.map { (it as? Movement.FlightMove)?.flight?.id ?: (it as Movement.TrainMove).train.id })
    }

    @Test fun after() {
        assertIs<TodayState.After>(q().state(LocalDate.parse("2027-05-30")))
    }

    @Test fun unknown() {
        assertIs<TodayState.Unknown>(q(Trip("x")).state(LocalDate.parse("2027-05-10")))
    }
}
