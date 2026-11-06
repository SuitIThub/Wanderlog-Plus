package de.wanderlogplus.shared.seatmap

import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeatMapsTest {
    @Test
    fun `rides find their model by number`() {
        assertEquals("ci-a359", SeatMaps.modelFor(Flight("f", airlineCode = "CI", number = "62"))?.key)
        assertEquals("ci-a359", SeatMaps.modelFor(Flight("f", airlineCode = "CI", number = "0062"))?.key)
        assertEquals("ci-a359", SeatMaps.modelFor(Flight("f", number = "ci 62"))?.key)
        assertEquals("ci-a21n", SeatMaps.modelFor(Flight("f", airlineCode = "CI", number = "105"))?.key)
        assertEquals("nh-789", SeatMaps.modelFor(Flight("f", airlineCode = "NH", number = "62", seatMap = "nh-789"))?.key)
        assertNull(SeatMaps.modelFor(Flight("f", airlineCode = "LH", number = "716")))
        assertEquals("db-ice4-2kl", SeatMaps.modelFor(Train("t", number = "ICE 571"))?.key)
        assertEquals("db-ice4-2kl", SeatMaps.modelFor(Train("t", number = "ICE1272"))?.key)
    }

    @Test
    fun `seats are placed with their position`() {
        val a350 = SeatMaps.byKey("ci-a359")!!
        val e = SeatMaps.locate(a350, "43E")!!
        assertEquals(SeatPosition.MIDDLE, e.position)
        assertEquals("Economy", e.section.name)
        assertTrue("43G" in e.neighbours && "43D" in e.neighbours, e.neighbours.toString())
        assertEquals(SeatPosition.AISLE, SeatMaps.locate(a350, "43G")!!.position)
        val a = SeatMaps.locate(a350, "34A")!!
        assertEquals(SeatPosition.WINDOW, a.position)
        assertEquals("Premium Economy", a.section.name)
        assertEquals(SeatPosition.AISLE, SeatMaps.locate(a350, "34C")!!.position)
        val a321 = SeatMaps.byKey("ci-a21n")!!
        assertEquals(SeatPosition.WINDOW, SeatMaps.locate(a321, "43K")!!.position)
        assertEquals(SeatPosition.MIDDLE, SeatMaps.locate(a321, "43J")!!.position)
        val ice = SeatMaps.byKey("db-ice4-2kl")!!
        assertEquals(SeatPosition.WINDOW, SeatMaps.locate(ice, "111")!!.position)
        assertEquals(SeatPosition.WINDOW, SeatMaps.locate(ice, "114")!!.position)
        assertEquals(SeatPosition.AISLE, SeatMaps.locate(ice, "115")!!.position)
        // ANA A321neo: row 10 has no window seats on the left.
        assertEquals(SeatPosition.AISLE, SeatMaps.locate(SeatMaps.byKey("nh-a21n")!!, "10C")!!.position)
        assertNull(SeatMaps.locate(SeatMaps.byKey("nh-a21n")!!, "10A"))
        assertNull(SeatMaps.locate(a350, "43F"))
        assertNull(SeatMaps.locate(a350, "  "))
    }

    @Test
    fun `catalog is consistent`() {
        assertEquals(setOf("db-ice4-2kl", "ci-a359", "ci-a21n", "nh-a21n", "nh-789"), SeatMaps.all.map { it.key }.toSet())
        SeatMaps.all.forEach { m ->
            val codes = m.sections.flatMap { s -> s.rows.flatMap { r -> r.cells.filterIsInstance<Cell.SeatCell>().map { it.code } } }
            assertEquals(codes.size, codes.toSet().size, "duplicate seats in ${m.key}")
            assertTrue(codes.isNotEmpty())
        }
        val matches = SeatMaps.all.flatMap { it.matches.map(SeatMaps::normalize) }
        assertEquals(matches.size, matches.toSet().size)
    }
}
