package de.wanderlogplus.app.maps

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.client.journeys
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitGeometryTest {
    private val nip = TransitStation("日本橋", "Nippombashi", 34.667, 135.506)
    private val shin = TransitStation("新大阪", "Shin-Ōsaka", 34.733, 135.500)
    private val hiro = TransitStation("広島", "Hiroshima", 34.397, 132.475)
    private val toka = TransitStation("十日市町", "Tōkaichi-machi", 34.396, 132.452)

    private val route = TransitRoute(
        "09:04", "11:32", 148, transfers = 2, date = "2027-05-20",
        legs = listOf(
            TransitLeg("御堂筋線", "Midōsuji", from = nip, to = shin, departure = "09:04", arrival = "09:20",
                stops = listOf(TransitStop("梅田", "Umeda", "09:15", 34.702, 135.497), TransitStop("中津", "Nakatsu", "09:17"))),
            TransitLeg("のぞみ", "Nozomi 9", from = shin, to = hiro, departure = "09:30", arrival = "10:55",
                stops = listOf(TransitStop("新神戸", "Shin-Kōbe", "09:42", 34.706, 135.195), TransitStop("岡山", "Okayama", "10:15", 34.666, 133.918))),
            TransitLeg("徒歩", walk = true, from = hiro, to = hiro),
            TransitLeg("広電", "Hiroden Linie 6", from = hiro, to = toka, departure = "11:05", arrival = "11:28"),
        ),
    )
    private val hotel = LatLngD(34.6663, 135.5074)
    private val goal = LatLngD(34.3977, 132.4491)

    @Test
    fun ridesInTheirColorsWithStopsAndWalks() {
        val g = transitGeometry(route, hotel, goal)!!
        val rides = g.segments!!.filter { !it.walk }
        assertEquals(listOf(RIDE_COLORS[0], RIDE_COLORS[1], RIDE_COLORS[2]), rides.map { it.color })
        // The subway runs through Umeda (Nakatsu has no position and is left out).
        assertEquals(listOf(LatLngD(34.667, 135.506), LatLngD(34.702, 135.497), LatLngD(34.733, 135.500)), rides[0].points)
        assertEquals(4, rides[1].points.size)
        val walks = g.segments!!.filter { it.walk }
        assertEquals(hotel, walks.first().points.first())
        assertEquals(goal, walks.last().points.last())
        assertTrue(walks.all { it.color == WALK_COLOR })
        assertEquals(3, g.minorStops.size)
        assertEquals(listOf(hotel, LatLngD(34.667, 135.506), LatLngD(34.733, 135.500), LatLngD(34.397, 132.475), LatLngD(34.396, 132.452), goal), g.stops)
    }

    @Test
    fun colorsContinueWithAnOffset() {
        val g = transitGeometry(route, null, null, colorOffset = 4)!!
        assertEquals(listOf(RIDE_COLORS[4], RIDE_COLORS[5], RIDE_COLORS[0]), g.segments!!.filter { !it.walk }.map { it.color })
    }

    @Test
    fun withoutStopPositionsStraightLines() {
        val bare = route.copy(legs = route.legs.map { it.copy(stops = it.stops.map { s -> s.copy(lat = null, lng = null) }) })
        val g = transitGeometry(bare, null, null)!!
        assertEquals(2, g.segments!!.first().points.size)
        assertTrue(g.minorStops.isEmpty())
    }

    @Test
    fun cacheKeyFollowsColors() {
        val a = transitGeometry(route, hotel, goal)!!
        val b = transitGeometry(route, hotel, goal, colorOffset = 1)!!
        assertNotEquals(a.cacheKey(300, 200), b.cacheKey(300, 200))
    }

    @Test
    fun trainWithConnectionAndJourneyKeepColorsRunning() {
        val result = TransitResult(nip, toka, "2027-05-20T09:00", routes = listOf(route))
        val first = Train("a", depart = Endpoint("Nippombashi", lat = 34.667, lng = 135.506, date = "2027-05-20", time = "09:04"),
            arrive = Endpoint("Shin-Ōsaka", lat = 34.733, lng = 135.5, date = "2027-05-20", time = "09:20"),
            transit = result.copy(routes = listOf(route.copy(legs = route.legs.take(1)))), journeyId = "J", journeyOrder = 0,
            walkFrom = Endpoint("Hotel", lat = hotel.lat, lng = hotel.lng))
        val second = Train("b", depart = Endpoint("Shin-Ōsaka", lat = 34.733, lng = 135.5, date = "2027-05-20", time = "09:30"),
            arrive = Endpoint("Hiroshima", lat = 34.397, lng = 132.475, date = "2027-05-20", time = "10:55"),
            transit = result.copy(routes = listOf(route.copy(legs = route.legs.drop(1).take(1)))), journeyId = "J", journeyOrder = 1)
        val s = Fixtures.trip().copy(trains = listOf(first, second), flights = emptyList())
        val single = routeFor(first, s)!!
        assertNotNull(single.segments)
        assertEquals(hotel, single.stops.first())
        val j = routeFor(journeys(s).single(), s)!!
        assertEquals(listOf(RIDE_COLORS[0], RIDE_COLORS[1]), j.segments!!.filter { !it.walk }.map { it.color })
    }
}
