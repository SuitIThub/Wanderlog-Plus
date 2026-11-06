package de.wanderlogplus.shared.seatmap

import de.wanderlogplus.shared.model.Seat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeatTextTest {
    @Test
    fun `seat lines name class, car once and the seats`() {
        assertEquals("2. Klasse · Wagen 12, 111 + 114", seatLine("2. Klasse", listOf(Seat("12", "111"), Seat("12", "114"))))
        assertEquals("Economy · 43E + 43G", seatLine("Economy", listOf(Seat(seat = "43E"), Seat(seat = "43G"))))
        assertEquals("Wagen 3, 12A + Wagen 4, 1A", seatLine(null, listOf(Seat("3", "12A"), Seat("4", "1A"))))
        assertEquals("Green Car", seatLine(" Green Car ", emptyList()))
        assertNull(seatLine(null, emptyList()))
        assertNull(seatLine(" ", emptyList()))
    }
}
