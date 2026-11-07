package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitRidesTest {
    private val tokyo = TransitStation("東京", "Tokyo", 35.68, 139.77)
    private val nagoya = TransitStation("名古屋", "Nagoya", 35.17, 136.88)
    private val naka = TransitStation("中津川", "Nakatsugawa", 35.50, 137.50)

    private fun ride(line: String, from: TransitStation, to: TransitStation, dep: String, arr: String) =
        TransitLeg(lineJa = line, line = line, from = from, to = to, departure = dep, arrival = arr, departurePlatform = "14")
    private fun walk(from: TransitStation, to: TransitStation) = TransitLeg(lineJa = "徒歩", line = "zu Fuß", walk = true, from = from, to = to)

    private fun result(vararg legs: TransitLeg, fare: Int = 0) = TransitResult(
        tokyo, naka, "2027-05-20T08:00",
        routes = listOf(TransitRoute(legs.first().departure, legs.last().arrival, 190, fareYen = fare, legs = legs.toList(), date = "2027-05-20")),
    )

    @Test
    fun walksAreSkipped() {
        val r = result(walk(tokyo, tokyo), ride("JR Shinkansen Nozomi 15", tokyo, nagoya, "08:00", "09:40"), walk(nagoya, nagoya), ride("JR Chūō-Hauptlinie", nagoya, naka, "09:55", "11:10"))
        val trains = TransitRides.ridesOf(r)
        assertEquals(2, trains.size)
        val b = trains[1]
        assertEquals("Nagoya", b.depart.name)
        assertEquals("09:55", b.depart.time)
        assertEquals("2027-05-20", b.depart.date)
        assertEquals("11:10", b.arrive.time)
        assertEquals(35.17, b.depart.lat)
        assertEquals("JR Chūō-Hauptlinie", b.number)
        assertEquals("JR", b.carrier)
        assertEquals(1, b.transit!!.routes.single().legs.size)
        assertEquals("14", b.transit!!.routes.single().legs.single().departurePlatform)
    }

    @Test
    fun crossesMidnight() {
        val trains = TransitRides.ridesOf(result(ride("A", tokyo, nagoya, "23:50", "23:58"), ride("B", nagoya, naka, "00:05", "00:30")))
        assertEquals("2027-05-20", trains[0].arrive.date)
        assertEquals("2027-05-21", trains[1].depart.date)
        assertEquals("2027-05-21", trains[1].arrive.date)
        val over = TransitRides.ridesOf(result(ride("C", tokyo, naka, "23:40", "00:10"))).single()
        assertEquals("2027-05-20", over.depart.date)
        assertEquals("2027-05-21", over.arrive.date)
    }

    @Test
    fun fareOnlyOnFirst() {
        val trains = TransitRides.ridesOf(result(ride("A", tokyo, nagoya, "08:00", "09:40"), ride("B", nagoya, naka, "09:55", "11:10"), fare = 6380))
        assertEquals(6380, trains[0].transit!!.routes.single().fareYen)
        assertEquals(0, trains[1].transit!!.routes.single().fareYen)
    }

    @Test
    fun singleRideAndWalksOnly() {
        assertEquals(1, TransitRides.ridesOf(result(walk(tokyo, tokyo), ride("A", tokyo, naka, "08:00", "11:00"))).size)
        assertTrue(TransitRides.ridesOf(result(walk(tokyo, nagoya))).isEmpty())
        assertTrue(TransitRides.ridesOf(TransitResult(tokyo, naka, "2027-05-20T08:00")).isEmpty())
    }

    @Test
    fun carriers() {
        assertEquals("JR", TransitRides.carrierOf("JR Shinkansen Nozomi 15"))
        assertEquals("Tokyo Metro", TransitRides.carrierOf("Tokyo Metro Ginza-Linie"))
        assertEquals("Kintetsu", TransitRides.carrierOf("Kintetsu Nara-Linie"))
        assertNull(TransitRides.carrierOf("Bus 205"))
    }
}
