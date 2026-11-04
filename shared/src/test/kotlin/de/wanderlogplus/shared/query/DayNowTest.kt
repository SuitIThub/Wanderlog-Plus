package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TravelMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayNowTest {
    private val hotel = Place("h", name = "Hotel", lat = 35.0037, lng = 135.7788)
    private val places = listOf(
        Place("a", name = "Kiyomizu-dera", lat = 34.9949, lng = 135.7850, minMinutes = 60, maxMinutes = 120),
        Place("b", name = "Moosgarten", lat = 35.0, lng = 135.78),
        Place("c", name = "Nara-Park", lat = 34.6851, lng = 135.8430),
    ).associateBy { it.id }
    private val items = listOf(
        DayItem("i1", "d", "a", placeId = "a", time = "09:00"),
        DayItem("n", "d", "b", DayItemKind.NOTE, text = "Geld abheben"),
        DayItem("i2", "d", "c", placeId = "b"),
        DayItem("i3", "d", "d", placeId = "c", time = "15:00"),
    )
    private val train = Movement.TrainMove(Train("t", "JR", depart = Endpoint("Kyoto", date = "2027-05-20", time = "13:00"), arrive = Endpoint("Nara", date = "2027-05-20", time = "13:45")))

    private fun at(now: String) = DayNow.layout(items, places, emptyList(), listOf(train), hotel, DayNow.mins(now)!!)

    @Test
    fun `times are planned or estimated and moves sorted in`() {
        val v = at("08:00")
        val visits = v.steps.filterIsInstance<DayStep.Visit>()
        assertEquals(listOf(9 * 60, 10 * 60 + 43, 15 * 60), visits.map { it.start }) // 09:00, after 90 min + 13 min walk, 15:00
        assertEquals(listOf(false, true, false), visits.map { it.estimated })
        assertEquals(TravelMode.WALK, visits[1].wayMode)
        assertEquals(TravelMode.TRANSIT, visits[2].wayMode)
        // Kiyomizu, note, Moosgarten, train 13:00, Nara-Park 15:00
        assertEquals(listOf("i1", "n", "i2", "t", "i3"), v.steps.map {
            when (it) { is DayStep.Visit -> it.item.id; is DayStep.Note -> it.item.id; is DayStep.Move -> "t" }
        })
        assertNull(v.current)
        assertEquals("i1", (v.next as DayStep.Visit).item.id)
        assertEquals(60 - visits[0].wayMinutes!!, v.leaveIn(DayNow.mins("08:00")!!))
    }

    @Test
    fun `current and next follow the clock`() {
        val v = at("09:30")
        assertEquals("i1", (v.current as DayStep.Visit).item.id)
        assertEquals("i2", (v.next as DayStep.Visit).item.id)
        val onTrain = at("13:10")
        assertTrue(onTrain.current is DayStep.Move)
        assertEquals(StepState.DONE, onTrain.steps.filterIsInstance<DayStep.Visit>().first { it.item.id == "i2" }.state)
        val evening = at("20:00")
        assertNull(evening.current)
        assertNull(evening.next)
        assertTrue(evening.steps.filter { it !is DayStep.Note }.all { it.state == StepState.DONE })
    }

    @Test
    fun `opening texts`() {
        val p = listOf(OpeningPeriod(0, "09:00", 0, "18:00"))
        assertEquals("offen bis 18:00", DayNow.openText(p, 0, DayNow.mins("12:00")!!))
        assertEquals("schließt in 25 Min.", DayNow.openText(p, 0, DayNow.mins("17:35")!!))
        assertEquals("geschlossen – öffnet 09:00", DayNow.openText(p, 0, DayNow.mins("07:00")!!))
        assertEquals("jetzt geschlossen", DayNow.openText(p, 0, DayNow.mins("19:00")!!))
        assertEquals("heute geschlossen", DayNow.openText(p, 1, DayNow.mins("12:00")!!))
        assertNull(DayNow.openText(emptyList(), 0, 600))
    }
}
