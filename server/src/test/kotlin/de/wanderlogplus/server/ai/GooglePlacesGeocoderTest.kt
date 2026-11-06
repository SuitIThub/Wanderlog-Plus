package de.wanderlogplus.server.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GooglePlacesGeocoderTest {
    private class FakeTransport(private val answer: Pair<Int, String>) : PlacesSearchTransport {
        val bodies = mutableListOf<String>()
        override fun searchText(body: String): Pair<Int, String> = answer.also { bodies += body }
    }

    @Test
    fun `the first place is the hit with its address and country`() {
        val t = FakeTransport(
            200 to """{"places":[{"id":"ChIJ1","formattedAddress":"Japan, 〒530-0012 Osaka, Kita-ku, Shibata 1-1","location":{"latitude":34.7055,"longitude":135.4983},"displayName":{"text":"むにむに 梅田店"}}]}""",
        )
        val h = GooglePlacesGeocoder(t).find("Munimuni Umeda Osaka")!!
        assertEquals(34.7055, h.lat)
        assertEquals(135.4983, h.lng)
        assertEquals("JP", h.country)
        assertTrue("Shibata" in h.address!!)
        val body = t.bodies.single()
        assertTrue("\"textQuery\":\"Munimuni Umeda Osaka\"" in body && "\"regionCode\":\"JP\"" in body && "\"pageSize\":1" in body, body)
    }

    @Test
    fun `japanese addresses without the country still count as japan`() {
        // With regionCode JP Google leaves the country out of Japanese addresses.
        val t = FakeTransport(200 to """{"places":[{"formattedAddress":"16-4 Dōyamachō, Kita Ward, Osaka, 530-0027","location":{"latitude":34.70386,"longitude":135.50304}}]}""")
        assertEquals("JP", GooglePlacesGeocoder(t).find("Munimuni Umeda Osaka")!!.country)
    }

    @Test
    fun `a place abroad keeps its own country`() {
        val t = FakeTransport(200 to """{"places":[{"formattedAddress":"Kilindi, Tansania","location":{"latitude":-5.5,"longitude":37.6}}]}""")
        assertEquals("Tansania", GooglePlacesGeocoder(t).find("Munimuni")!!.country)
    }

    @Test
    fun `no places is no hit, a failing api is unavailable`() {
        assertNull(GooglePlacesGeocoder(FakeTransport(200 to "{}")).find("Nirgendwo"))
        assertFailsWith<GeocoderUnavailable> { GooglePlacesGeocoder(FakeTransport(500 to "")).find("x") }
        assertFailsWith<GeocoderUnavailable> { GooglePlacesGeocoder(FakeTransport(403 to "")).find("x") }
    }

    @Test
    fun `the fallback asks the second geocoder when the first finds nothing or is down`() {
        val photon = Geocoder { q -> if (q == "Osaka") GeoHit(34.69, 135.50, "Osaka", "JP") else null }
        val empty = FallbackGeocoder(Geocoder { null }, photon)
        assertEquals(135.50, empty.find("Osaka")!!.lng)
        val down = FallbackGeocoder(Geocoder { throw GeocoderUnavailable() }, photon)
        assertEquals(135.50, down.find("Osaka")!!.lng)
        val google = FallbackGeocoder(Geocoder { GeoHit(1.0, 2.0, "g", "JP") }, photon)
        assertEquals(2.0, google.find("Osaka")!!.lng)
    }
}
