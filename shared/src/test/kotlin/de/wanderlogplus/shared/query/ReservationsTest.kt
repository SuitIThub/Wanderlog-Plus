package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.CriterionScore
import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReservationsTest {
    @Test
    fun `reason from research, hints, tags and notes, not from a hint that says it is not needed`() {
        val hard = SavedPlace("a", "pa", dossier = PlaceDossier(criteria = listOf(CriterionScore("booking", score = 1, note = "Nur per Postkarte Wochen vorher"))))
        assertEquals("Nur per Postkarte Wochen vorher", Reservations.reason(hard))
        assertEquals("Buchbarkeit 2/5", Reservations.reason(SavedPlace("b", "pb", dossier = PlaceDossier(criteria = listOf(CriterionScore("booking", score = 2))))))
        assertNull(Reservations.reason(SavedPlace("c", "pc", dossier = PlaceDossier(criteria = listOf(CriterionScore("booking", score = 4))))))

        val hint = SavedPlace("d", "pd", dossier = PlaceDossier(hints = listOf("Montags geschlossen", "Tickets mindestens 2 Wochen vorher online kaufen")))
        assertEquals("Tickets mindestens 2 Wochen vorher online kaufen", Reservations.reason(hint))
        assertNull(Reservations.reason(SavedPlace("e", "pe", dossier = PlaceDossier(goodToKnow = listOf("Keine Reservierung nötig, einfach hingehen")))))
        assertEquals("Tag „Nur mit Reservierung“", Reservations.reason(SavedPlace("f", "pf", tags = listOf("Nur mit Reservierung"))))
        assertEquals("Tisch reservieren!", Reservations.reason(SavedPlace("g", "pg", note = "Tisch reservieren!")))
        assertNull(Reservations.reason(SavedPlace("h", "ph", note = "Schöner Blick")))
    }

    @Test
    fun `open reservations are planned from today on, not reserved and not dropped`() {
        val need = PlaceDossier(hints = listOf("Reservierung nötig"))
        val saved = listOf(
            SavedPlace("s1", "p1", dossier = need),
            SavedPlace("s2", "p2", dossier = need, reserved = true),
            SavedPlace("s3", "p3", dossier = need, status = PlaceStatus.DROPPED),
            SavedPlace("s4", "p4", dossier = need), // only on a past day
            SavedPlace("s5", "p5"), // needs nothing
            SavedPlace("s6", "p6", dossier = need),
        )
        val days = listOf(Day("d1", "2027-05-01"), Day("d2", "2027-05-10"), Day("d3", "2027-05-05"))
        val items = listOf(
            DayItem("i1", "d2", "a", placeId = "p1"),
            DayItem("i1b", "d3", "a", placeId = "p1"),
            DayItem("i2", "d2", "b", placeId = "p2"),
            DayItem("i3", "d2", "c", placeId = "p3"),
            DayItem("i4", "d1", "a", placeId = "p4"),
            DayItem("i5", "d2", "d", placeId = "p5"),
            DayItem("i6", "d2", "e", placeId = "p6"),
        )
        val open = Reservations.open(saved, days, items, "2027-05-03")
        assertEquals(listOf("s1" to "2027-05-05", "s6" to "2027-05-10"), open.map { it.saved.id to it.date })
        assertEquals("Reservierung nötig", open.first().reason)
    }

    @Test
    fun `negations with filler words do not count`() {
        assertNull(Reservations.reason(SavedPlace("a", "pa", dossier = PlaceDossier(hints = listOf("Eine Reservierung ist nicht nötig.")))))
        assertNull(Reservations.reason(SavedPlace("b", "pb", dossier = PlaceDossier(tips = listOf("Reservation not required, just walk in")))))
        val denying = PlaceDossier(criteria = listOf(CriterionScore("booking", score = 2, note = "Reservierung ist nicht erforderlich, aber Andrang")))
        assertNull(Reservations.reason(SavedPlace("c", "pc", dossier = denying)))
    }

    @Test
    fun `explicit research advice wins over texts`() {
        val notNeeded = PlaceDossier(reservation = de.wanderlogplus.shared.model.ReservationAdvice(de.wanderlogplus.shared.model.ReservationNeed.NOT_NEEDED))
        assertNull(Reservations.reason(SavedPlace("a", "pa", tags = listOf("⚠️ Nur mit Reservierung"), dossier = notNeeded)))
        fun advice(need: de.wanderlogplus.shared.model.ReservationNeed, note: String = "") =
            SavedPlace("b", "pb", dossier = PlaceDossier(reservation = de.wanderlogplus.shared.model.ReservationAdvice(need, note = note)))
        assertEquals("Tickets online", Reservations.reason(advice(de.wanderlogplus.shared.model.ReservationNeed.REQUIRED, "Tickets online")))
        assertEquals("Reservierung nötig", Reservations.reason(advice(de.wanderlogplus.shared.model.ReservationNeed.REQUIRED)))
        assertEquals("Reservierung empfohlen", Reservations.reason(advice(de.wanderlogplus.shared.model.ReservationNeed.RECOMMENDED)))
        // Unknown: the texts decide as before.
        val unknown = PlaceDossier(hints = listOf("Reservierung nötig"), reservation = de.wanderlogplus.shared.model.ReservationAdvice())
        assertEquals("Reservierung nötig", Reservations.reason(SavedPlace("c", "pc", dossier = unknown)))
    }
}
