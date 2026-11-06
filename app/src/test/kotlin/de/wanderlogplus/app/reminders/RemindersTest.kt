package de.wanderlogplus.app.reminders

import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RemindersTest {
    private val places = mapOf("p" to Place(id = "p", name = "Hotel Sakura"))
    private fun stay(deadline: String? = "2026-11-10", id: String = "s1") = Stay(
        id = id, placeId = "p", cancellationDeadline = deadline,
        confirmationNumber = "CONF-998877", travelerNames = listOf("Max Mustermann"),
    )
    private fun due(today: String, sent: Set<String> = emptySet(), s: Stay = stay()) =
        dueReminders(listOf(s), places, LocalDate.parse(today), sent)

    @Test fun sevenDaysBefore() {
        val r = due("2026-11-03").single()
        assertEquals("s1|2026-11-10|7", r.key)
        assertEquals("Stornofrist: Hotel Sakura", r.title)
        assertTrue(r.text, r.text.startsWith("Noch 7 Tage (bis "))
        assertTrue(due("2026-11-02").isEmpty())
    }

    @Test fun onlySmallestDueOffset() {
        assertEquals("s1|2026-11-10|3", due("2026-11-08").single().key)
    }

    @Test fun notResentWhenAlreadySent() {
        assertTrue(due("2026-11-08", setOf("s1|2026-11-10|3")).isEmpty())
        // the older offset is not caught up
        assertTrue(due("2026-11-08", setOf("s1|2026-11-10|7")).single().key.endsWith("|3"))
    }

    @Test fun nothingAfterDeadline() {
        assertTrue(due("2026-11-11").isEmpty())
    }

    @Test fun textHasNoConfirmationNumber() {
        for (day in listOf("2026-11-03", "2026-11-08", "2026-11-09", "2026-11-10")) {
            val r = due(day).single()
            for (t in listOf(r.title, r.text, r.key)) {
                assertFalse(t.contains("CONF-998877"))
                assertFalse(t.contains("Mustermann"))
            }
        }
    }

    @Test fun todayAndTomorrowWording() {
        assertTrue(due("2026-11-09").single().text.startsWith("Morgen"))
        assertTrue(due("2026-11-10").single().text.startsWith("Heute"))
    }

    @Test fun datetimeDeadlineAndInvalidAndBlankName() {
        assertEquals("s1|2026-11-10|0", due("2026-11-10", s = stay("2026-11-10T18:00")).single().key)
        assertTrue(due("2026-11-10", s = stay("kaputt")).isEmpty())
        assertTrue(due("2026-11-10", s = stay(null)).isEmpty())
        val noName = dueReminders(listOf(stay().copy(placeId = "x")), places, LocalDate.parse("2026-11-10"), emptySet())
        assertEquals("Stornofrist: Unterkunft", noName.single().title)
    }

    private fun window(kind: de.wanderlogplus.shared.query.DeadlineKind, date: String, type: de.wanderlogplus.shared.query.DeadlineTarget = de.wanderlogplus.shared.query.DeadlineTarget.TRAIN, status: de.wanderlogplus.shared.model.PlaceStatus? = null) =
        de.wanderlogplus.shared.query.Deadline(kind, date, "Nozomi 21", type, "t1", de.wanderlogplus.shared.query.DeadlineState.UPCOMING, placeStatus = status)

    @Test fun byRemindersUseOffsets() {
        val by = window(de.wanderlogplus.shared.query.DeadlineKind.BY, "2027-04-20")
        assertTrue(dueWindowReminders(listOf(by), LocalDate.parse("2027-03-20"), emptySet()).isEmpty())
        val r = dueWindowReminders(listOf(by), LocalDate.parse("2027-04-13"), emptySet()).single()
        assertEquals("win|TRAIN:t1|2027-04-20|7", r.key)
        assertEquals("Reservieren: Nozomi 21", r.title)
        assertTrue(r.text, r.text.startsWith("Noch 7 Tage (bis "))
        assertEquals("TRAIN" to "t1", r.doneType to r.doneId)
        assertTrue(dueWindowReminders(listOf(by), LocalDate.parse("2027-04-13"), setOf(r.key)).isEmpty())
        assertTrue(dueWindowReminders(listOf(by), LocalDate.parse("2027-04-21"), emptySet()).isEmpty())
    }

    @Test fun opensOnTheDay() {
        // Not yet open: nothing.
        assertTrue(dueWindowReminders(listOf(window(de.wanderlogplus.shared.query.DeadlineKind.OPENS, "2027-04-12")), LocalDate.parse("2027-04-10"), emptySet()).isEmpty())
        // Open now (from the opening day on): one "Jetzt reservierbar", then the usual "bis" steps.
        val open = window(de.wanderlogplus.shared.query.DeadlineKind.BY, "2027-04-20").copy(state = de.wanderlogplus.shared.query.DeadlineState.OPEN_NOW)
        val r = dueWindowReminders(listOf(open), LocalDate.parse("2027-04-10"), emptySet()).single()
        assertEquals("win|TRAIN:t1|open|2027-04-20", r.key)
        assertEquals("Jetzt reservierbar: Nozomi 21", r.title)
        assertEquals(listOf("win|TRAIN:t1|2027-04-20|14"), r.covers)
        assertTrue(dueWindowReminders(listOf(open), LocalDate.parse("2027-04-11"), setOf(r.key) + r.covers).isEmpty())
        assertEquals("win|TRAIN:t1|2027-04-20|7", dueWindowReminders(listOf(open), LocalDate.parse("2027-04-13"), setOf(r.key) + r.covers).single().key)
    }

    @Test fun placesOnlyWhenPlanned() {
        val idea = window(de.wanderlogplus.shared.query.DeadlineKind.BY, "2027-04-20", de.wanderlogplus.shared.query.DeadlineTarget.PLACE, de.wanderlogplus.shared.model.PlaceStatus.IDEA)
        assertTrue(dueWindowReminders(listOf(idea), LocalDate.parse("2027-04-18"), emptySet()).isEmpty())
        val planned = idea.copy(placeStatus = de.wanderlogplus.shared.model.PlaceStatus.PLANNED)
        assertEquals("PLACE" to "t1", dueWindowReminders(listOf(planned), LocalDate.parse("2027-04-18"), emptySet()).single().let { it.doneType to it.doneId })
        // Cancellation deadlines and plain "needs a reservation" rows have their own reminders.
        assertTrue(dueWindowReminders(listOf(window(de.wanderlogplus.shared.query.DeadlineKind.CANCEL, "2027-04-20")), LocalDate.parse("2027-04-18"), emptySet()).isEmpty())
    }

    @Test fun doneFromTheNotificationEndsTheWindow() {
        val store = de.wanderlogplus.shared.sync.InMemoryEntityStore()
        val editor = de.wanderlogplus.client.LocalEditor(store, de.wanderlogplus.client.InMemoryOutbox(), de.wanderlogplus.shared.sync.HlcClock("dev", now = { 1000L }))
        val train = de.wanderlogplus.shared.model.Train("t1", reservation = de.wanderlogplus.shared.model.ReservationWindow(by = "2027-04-20"))
        editor.create(de.wanderlogplus.shared.sync.EntityType.TRAIN, de.wanderlogplus.shared.model.EntityCodec.fields(train), id = "t1")
        val snap = { de.wanderlogplus.client.TripSnapshot.from(store.all()) }
        ReservationDoneReceiver.markWindowDone(editor, snap(), "TRAIN", "t1")
        assertEquals(de.wanderlogplus.shared.model.ReservationWindow(by = "2027-04-20", done = true), snap().trains.single().reservation)
        val list = de.wanderlogplus.shared.query.Deadlines.list(
            de.wanderlogplus.shared.query.DeadlineInput(emptyMap(), emptyList(), emptyList(), snap().trains, emptyList(), emptyList(), emptyList(), emptyList()), "2027-04-10",
        )
        assertTrue(dueWindowReminders(list, LocalDate.parse("2027-04-13"), emptySet()).isEmpty())
    }
}
