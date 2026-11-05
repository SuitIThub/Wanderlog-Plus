package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayFlowTest {
    private val regions = listOf(Region("tokyo", "Tokio"), Region("kiso", "Kiso"), Region("nikko", "Nikko", dayTripFromRegionId = "tokyo"))
    private val places = listOf(
        Place("p-hotel", name = "Hotel Tokio", lat = 35.68, lng = 139.76),
        Place("p-ryokan", name = "Ryokan Kiso", lat = 35.52, lng = 137.57),
        Place("p-museum", name = "Museum", lat = 35.69, lng = 139.70),
        Place("p-nikko", name = "Nikko", lat = 36.75, lng = 139.60),
        Place("p-magome", name = "Magome", lat = 35.53, lng = 137.56),
        Place("p-free", name = "Ohne Region", lat = 35.50, lng = 137.50),
        Place("p-cafe", name = "Café ohne Ort"),
    ).associateBy { it.id }
    private val saved = listOf(
        SavedPlace("s1", "p-museum", "tokyo"), SavedPlace("s2", "p-magome", "kiso"), SavedPlace("s3", "p-nikko", "nikko"),
        SavedPlace("s4", "p-free"), SavedPlace("s5", "p-cafe"),
    )
    private val tokyo = Stay("st1", "p-hotel", "tokyo", "2027-05-10", "2027-05-14", checkOutTime = "10:00")
    private val kiso = Stay("st2", "p-ryokan", "kiso", "2027-05-14", "2027-05-16", checkInTime = "15:00")
    private val train = Train("t1", "JR", Endpoint("Tokyo", lat = 35.681, lng = 139.767, date = "2027-05-14", time = "11:33"), Endpoint("Nakatsugawa", lat = 35.49, lng = 137.50, date = "2027-05-14", time = "14:40"))
    private val date = "2027-05-14"

    private fun item(id: String, placeId: String?, time: String? = null, kind: DayItemKind = DayItemKind.PLACE) =
        DayItem(id, "d", id, kind = kind, placeId = placeId, time = time)

    private fun flow(items: List<DayItem>, stays: List<Stay> = listOf(tokyo, kiso), rides: List<Movement> = listOf(Movement.TrainMove(train)), on: String = date) =
        DayFlow.build(on, items, rides, stays, saved, places, regions)

    private fun DayFlowView.names() = entries.map {
        when (it) {
            is FlowEntry.Start -> "start:" + it.stay.id + (if (it.checkOut) "!" else "")
            is FlowEntry.Item -> it.item.id + (if (it.checkIn != null) "+in" else "")
            is FlowEntry.Ride -> "ride"
            is FlowEntry.Night -> "night:" + it.stay.id + (if (it.checkIn) "+in" else "")
        }
    }

    @Test fun aMovingDayIsSortedByRegionAroundTheRide() {
        // Program order is mixed up on purpose: the region decides the side of the ride.
        val f = flow(listOf(item("magome", "p-magome"), item("ryokan", "p-ryokan"), item("museum", "p-museum"), item("nikko", "p-nikko")))
        assertEquals(listOf("start:st1!", "museum", "nikko", "ride", "magome", "ryokan+in", "night:st2"), f.names())
        assertEquals(
            listOf("museum", "nikko", "ride:TRAIN:t1", "magome", "ryokan"),
            f.legs.map { it.key },
            "hotel → museum → nikko → station; arrival → magome → ryokan; no way from the ryokan to itself for the night",
        )
        assertEquals(3, f.legTo(3)!!.to)
    }

    @Test fun withoutARegionTheTimeThenThePositionDecides() {
        val f = flow(listOf(item("late", "p-cafe", time = "16:00"), item("near", "p-free"), item("note", null, kind = DayItemKind.NOTE)))
        assertEquals(listOf("start:st1!", "ride", "late", "near", "note", "night:st2+in"), f.names())
        assertEquals("night", f.legs.last().key)
    }

    @Test fun aQuietDayRunsFromTheStayBackToIt() {
        val f = flow(listOf(item("museum", "p-museum")), rides = emptyList(), on = "2027-05-12")
        assertEquals(listOf("start:st1", "museum", "night:st1"), f.names())
        assertEquals(listOf("museum", "night"), f.legs.map { it.key })
    }

    @Test fun arrivalAndDepartureDaysHaveOnlyOneStay() {
        val arrival = flow(listOf(item("museum", "p-museum")), stays = listOf(tokyo), rides = emptyList(), on = "2027-05-10")
        assertEquals(listOf("museum", "night:st1+in"), arrival.names())
        val departure = flow(emptyList(), stays = listOf(tokyo), on = date)
        assertEquals(listOf("start:st1!", "ride"), departure.names())
        assertEquals(listOf("ride:TRAIN:t1"), departure.legs.map { it.key })
        assertTrue(DayFlow.isDayLegKey("ride:TRAIN:t1") && DayFlow.isDayLegKey("night") && !DayFlow.isDayLegKey("i1"))
    }

    @Test fun twoRidesInARowHaveNoWayBetweenThem() {
        val second = Train("t2", "Bus", Endpoint("Nakatsugawa", date = date, time = "15:00"), Endpoint("Magome", date = date, time = "15:30"))
        val f = flow(emptyList(), rides = listOf(Movement.TrainMove(train), Movement.TrainMove(second)))
        assertEquals(listOf("start:st1!", "ride", "ride", "night:st2+in"), f.names())
        assertEquals(listOf("ride:TRAIN:t1", "night"), f.legs.map { it.key })
    }
}
