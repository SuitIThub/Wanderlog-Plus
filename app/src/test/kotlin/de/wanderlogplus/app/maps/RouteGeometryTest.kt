package de.wanderlogplus.app.maps

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Train
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteGeometryTest {
    private val a = LatLngD(35.0, 135.0)
    private val b = LatLngD(36.0, 137.0)

    @Test fun trainIsStraight() {
        assertEquals(listOf(a, b), RouteGeometry(a, b, arc = false).points())
    }

    @Test fun flightArcHas32PointsAndBulges() {
        val pts = RouteGeometry(a, b, arc = true).points()
        assertEquals(32, pts.size)
        assertEquals(a.lat, pts.first().lat, 1e-9)
        assertEquals(a.lng, pts.first().lng, 1e-9)
        assertEquals(b.lat, pts.last().lat, 1e-9)
        assertEquals(b.lng, pts.last().lng, 1e-9)
        // Distance of the middle point from the straight line a-b (in degree space).
        val mid = pts[pts.size / 2]
        val dx = b.lng - a.lng
        val dy = b.lat - a.lat
        val dist = abs(dy * (mid.lng - a.lng) - dx * (mid.lat - a.lat)) / Math.hypot(dx, dy)
        assertTrue("arc should bulge away from the straight line, was $dist", dist > 0.1)
    }

    @Test fun cacheKeyStableAndSizeSensitive() {
        val r = RouteGeometry(a, b, arc = true)
        val k = r.cacheKey(600, 240)
        assertEquals(k, RouteGeometry(LatLngD(35.0, 135.0), LatLngD(36.0, 137.0), true).cacheKey(600, 240))
        assertEquals(64, k.length)
        assertTrue(k.all { it in '0'..'9' || it in 'a'..'f' })
        // Rounded to 5 decimals: tiny jitter keeps the key.
        assertEquals(k, RouteGeometry(LatLngD(35.000001, 135.0), b, true).cacheKey(600, 240))
        assertNotEquals(k, r.cacheKey(601, 240))
        assertNotEquals(k, r.cacheKey(600, 241))
        assertNotEquals(k, RouteGeometry(a, b, arc = false).cacheKey(600, 240))
        assertNotEquals(k, RouteGeometry(LatLngD(35.0001, 135.0), b, true).cacheKey(600, 240))
    }

    @Test fun routeForMissingCoordsIsNull() {
        val snap = Fixtures.trip()
        val noPlace = Train("t", depart = Endpoint("A", placeId = "missing"), arrive = Endpoint("B", placeId = "p-bhf-kueste"))
        assertNull(routeFor(noPlace, snap))
        val noId = Train("t", depart = Endpoint("A"), arrive = Endpoint("B", placeId = "p-bhf-kueste"))
        assertNull(routeFor(noId, snap))
        val zero = snap.copy(places = snap.places + ("p-zero" to Place("p-zero", name = "Null", lat = 0.0, lng = 0.0)))
        val f = Flight("f", depart = Endpoint("Null", placeId = "p-zero"), arrive = Endpoint("Süd", placeId = "p-flughafen-b"))
        assertNull(routeFor(f, zero))
    }

    @Test fun routeForUsesEndpointPlaces() {
        val snap = Fixtures.trip()
        val train = routeFor(snap.trains.single(), snap)!!
        assertEquals(RouteGeometry(LatLngD(34.98, 135.75), LatLngD(34.70, 135.49), arc = false), train)
        val flight = routeFor(snap.flights.first { it.id == "fl-1" }, snap)!!
        assertEquals(RouteGeometry(LatLngD(35.55, 139.78), LatLngD(34.43, 135.23), arc = true), flight)
    }
}
