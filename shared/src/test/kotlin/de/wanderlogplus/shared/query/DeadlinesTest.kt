package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.ReservationAdvice
import de.wanderlogplus.shared.model.ReservationNeed
import de.wanderlogplus.shared.model.ReservationWindow
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadlinesTest {
    private val today = "2027-04-10"
    private val days = listOf(Day("d1", "2027-05-01"), Day("d10", "2027-05-10"))
    private val tripDays = listOf(
        DayView("2027-05-01", null, "r0", null, false),
        DayView("2027-05-03", null, "r1", null, false),
        DayView("2027-05-04", null, "r1", null, false),
    )
    private val planned = DayItem("i1", "d10", "a0", DayItemKind.PLACE, "p1")

    private fun input(saved: List<SavedPlace> = emptyList(), stays: List<Stay> = emptyList(), trains: List<Train> = emptyList()) =
        DeadlineInput(mapOf("p1" to Place("p1", name = "Ghibli")), saved, stays, trains, emptyList(), days, listOf(planned), tripDays)

    @Test
    fun `visit date is planned or estimated by region`() {
        assertEquals("2027-05-10" to false, ReservationDates.visitDate(SavedPlace("s1", "p1"), days, listOf(planned), tripDays, today))
        assertEquals("2027-05-03" to true, ReservationDates.visitDate(SavedPlace("s2", "p2", regionId = "r1"), days, listOf(planned), tripDays, today))
        assertNull(ReservationDates.visitDate(SavedPlace("s3", "p3"), days, listOf(planned), tripDays, today))
    }

    @Test
    fun `window from advice, fixed day wins, by hand stays`() {
        val advice = ReservationAdvice(ReservationNeed.REQUIRED, opensDaysBefore = 30, byDaysBefore = 3)
        val s = SavedPlace("s1", "p1", dossier = PlaceDossier(reservation = advice))
        assertEquals(ReservationWindow(opens = "2027-04-10", by = "2027-05-07"), ReservationDates.windowFor(s, "2027-05-10" to false))
        val fixed = s.copy(dossier = PlaceDossier(reservation = advice.copy(opensOn = "2027-04-01")))
        assertEquals("2027-04-01", ReservationDates.windowFor(fixed, "2027-05-10" to false)?.opens)
        val hand = ReservationWindow(opens = "2027-03-01", byHand = true)
        assertEquals(hand, ReservationDates.windowFor(s.copy(reservationWindow = hand), "2027-05-10" to false))
        assertNull(ReservationDates.windowFor(s, null)?.by)
    }

    @Test
    fun `logistics windows become states`() {
        fun train(w: ReservationWindow?, date: String = "2027-05-02") = Train("t1", carrier = "Nozomi", number = "21", depart = Endpoint("Tokyo", date = date), reservation = w)
        val open = Deadlines.list(input(trains = listOf(train(ReservationWindow(opens = "2027-04-05", by = "2027-04-20")))), today)
        assertEquals(listOf(DeadlineKind.BY to DeadlineState.OPEN_NOW), open.map { it.kind to it.state })
        assertEquals("Nozomi 21", open.single().title)
        val later = Deadlines.list(input(trains = listOf(train(ReservationWindow(opens = "2027-04-20", by = "2027-04-25")))), today)
        assertEquals(listOf(DeadlineKind.OPENS to DeadlineState.UPCOMING, DeadlineKind.BY to DeadlineState.UPCOMING), later.map { it.kind to it.state })
        val late = Deadlines.list(input(trains = listOf(train(ReservationWindow(by = "2027-04-01")))), today)
        assertEquals(DeadlineState.OVERDUE, late.single().state)
        assertTrue(Deadlines.list(input(trains = listOf(train(ReservationWindow(by = "2027-04-01", done = true)))), today).isEmpty())
        assertTrue(Deadlines.list(input(trains = listOf(train(ReservationWindow(by = "2027-04-01"), date = "2027-04-05"))), today).isEmpty())
    }

    @Test
    fun `stay cancellation and places that need a reservation`() {
        val stay = Stay("st", "p1", checkIn = "2027-05-01", checkOut = "2027-05-03", cancellationDeadline = "2027-04-15")
        val need = PlaceDossier(hints = listOf("Tickets mindestens 2 Wochen vorher online kaufen"))
        val list = Deadlines.list(input(saved = listOf(SavedPlace("s1", "p1", dossier = need, status = PlaceStatus.PLANNED)), stays = listOf(stay)), today)
        assertEquals(listOf(DeadlineKind.CANCEL to "2027-04-15", DeadlineKind.PLACE to "2027-05-10"), list.map { it.kind to it.date })
        assertEquals(PlaceStatus.PLANNED, list.last().placeStatus)
        assertTrue(Deadlines.list(input(saved = listOf(SavedPlace("s1", "p1", dossier = need, reserved = true))), today).isEmpty())
        assertTrue(Deadlines.list(input(saved = listOf(SavedPlace("s1", "p1", dossier = need, status = PlaceStatus.DROPPED))), today).isEmpty())
        // Estimated from the region, with the research's rule.
        val advice = PlaceDossier(reservation = ReservationAdvice(ReservationNeed.REQUIRED, opensDaysBefore = 20))
        val est = Deadlines.list(input(saved = listOf(SavedPlace("s2", "p2", regionId = "r1", dossier = advice))), today)
        assertEquals(listOf(DeadlineKind.OPENS to "2027-04-13"), est.map { it.kind to it.date })
        assertTrue(est.single().estimated)
    }

    @Test
    fun `a place on a day counts as planned`() {
        val need = PlaceDossier(hints = listOf("Tickets mindestens 2 Wochen vorher online kaufen"))
        val list = Deadlines.list(input(saved = listOf(SavedPlace("s1", "p1", dossier = need, status = PlaceStatus.IDEA))), today)
        assertEquals(PlaceStatus.PLANNED, list.single().placeStatus)
    }
}
