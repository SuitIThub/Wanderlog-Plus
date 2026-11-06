package de.wanderlogplus.server

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GooglePlaceDetailsTest {
    private val tmp = Files.createTempDirectory("wlp-gdetails")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val sync = SyncService(store)

    private val answer = """{"id":"ChIJmuni","displayName":{"text":"Taishu Sakaba Munimuni Umeda"},
        "regularOpeningHours":{"periods":[{"open":{"day":1,"hour":17,"minute":0},"close":{"day":1,"hour":23,"minute":30}},
          {"open":{"day":6,"hour":15,"minute":0},"close":{"day":0,"hour":1,"minute":0}}]},
        "rating":4.1,"userRatingCount":412,"priceLevel":"PRICE_LEVEL_MODERATE","websiteUri":"https://muni.example",
        "internationalPhoneNumber":"+81 6-1234-5678","googleMapsUri":"https://maps.google.com/?cid=1",
        "primaryTypeDisplayName":{"text":"Izakaya"},"editorialSummary":{"text":"Lebhafte Kneipe."},"businessStatus":"OPERATIONAL"}"""

    private class Fake(private val answers: Map<String, Pair<Int, String>>) : PlaceDetailsTransport {
        val asked = mutableListOf<String>()
        override fun details(googlePlaceId: String): Pair<Int, String> { asked += googlePlaceId; return answers[googlePlaceId] ?: (404 to "") }
    }

    @Test
    fun `details are parsed and cached`() {
        val t = Fake(mapOf("ChIJmuni" to (200 to answer)))
        val d = GooglePlaceDetails(store, t).get("ChIJmuni")!!
        assertEquals(listOf(OpeningPeriod(1, "17:00", 1, "23:30"), OpeningPeriod(6, "15:00", 0, "01:00")), d.openingPeriods)
        assertEquals(4.1, d.rating)
        assertEquals(412, d.numRatings)
        assertEquals(2, d.priceLevel)
        assertEquals("https://muni.example", d.website)
        assertEquals("+81 6-1234-5678", d.phone)
        assertEquals("Izakaya", d.type)
        GooglePlaceDetails(store, t).get("ChIJmuni")
        assertEquals(1, t.asked.size, "the second ask comes from the cache")
        assertNull(GooglePlaceDetails(store, t).get("ChIJnope"))
    }

    @Test
    fun `facts for research name hours, rating and price`() {
        val d = GooglePlaceDetails(store, Fake(mapOf("ChIJmuni" to (200 to answer)))).get("ChIJmuni")!!
        val text = d.facts().joinToString("\n") { it.text }
        assertTrue("Öffnungszeiten laut Google: Mo 17:00–23:30, Sa 15:00–01:00" in text, text)
        assertTrue("Bewertung bei Google: 4,1 von 5 (412 Bewertungen)" in text, text)
        assertTrue("Preisniveau laut Google: mittel" in text, text)
        assertTrue(d.facts().all { it.sources == listOf("https://maps.google.com/?cid=1") })
    }

    private fun seedPlace(p: Place) {
        var rec = EntityRecord(EntityType.PLACE, p.id)
        (EntityCodec.fields(p) + ("deleted" to JsonPrimitive(false))).forEach { (k, v) -> rec = rec.apply(FieldChange(EntityType.PLACE, p.id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun place(id: String) = EntityCodec.decode<Place>(store.get(EntityType.PLACE, id)!!)

    @Test
    fun `places with a google id get the empty fields filled once`() {
        seedPlace(Place("p-muni", googlePlaceId = "ChIJmuni", name = "Munimuni", website = "https://own.example"))
        seedPlace(Place("p-wl", googlePlaceId = "ChIJwl", name = "Wanderlog-Ort", rating = 4.5, openingPeriods = listOf(OpeningPeriod(1, "09:00", 1, "17:00"))))
        seedPlace(Place("p-none", name = "Ohne Google"))
        val t = Fake(mapOf("ChIJmuni" to (200 to answer)))
        val enricher = PlaceEnricher(store, sync, GooglePlaceDetails(store, t))
        assertEquals(1, enricher.enrichAll())
        val m = place("p-muni")
        assertEquals(4.1, m.rating)
        assertEquals(2, m.openingPeriods.size)
        assertEquals("https://own.example", m.website, "a field the user filled stays")
        assertEquals("+81 6-1234-5678", m.phone)
        assertEquals(listOf("ChIJmuni"), t.asked, "places that already have hours and a rating are not asked about")
        assertEquals(0, enricher.enrichAll())
        assertEquals(1, t.asked.size, "nothing is asked twice")
    }
}
