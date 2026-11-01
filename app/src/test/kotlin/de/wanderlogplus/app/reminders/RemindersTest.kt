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
}
