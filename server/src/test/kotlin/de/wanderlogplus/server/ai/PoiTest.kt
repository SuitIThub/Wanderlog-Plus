package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.PlacePoi
import de.wanderlogplus.shared.model.PoiKinds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoiTest {
    private val anchorLat = 34.9671
    private val anchorLng = 135.7727

    @Test fun parsesPoisNormalisesKindsAndLimits() {
        val arr = Json.parseToJsonElement(
            """[{"name":"Nordtor","kind":"ENTRANCE","lat":34.968,"lng":135.773,"note":"weniger voll"},
               {"name":" ","kind":"viewpoint"},{"name":"nordtor","kind":"entrance"},
               {"name":"Irgendwas","kind":"spaceship","lat":"x"}]""",
        ) as JsonArray
        val pois = parsePois(arr)
        assertEquals(listOf("Nordtor", "Irgendwas"), pois.map { it.name })
        assertEquals(PoiKinds.ENTRANCE, pois[0].kind)
        assertEquals(PoiKinds.OTHER, pois[1].kind)
        assertEquals(0.0, pois[1].lat)
        val many = Json.parseToJsonElement((1..10).joinToString(",", "[", "]") { """{"name":"P$it"}""" }) as JsonArray
        assertEquals(MAX_POIS, parsePois(many).size)
    }

    @Test fun otherBranchesMayLieAcrossTheCityButNotFurther() {
        val geocoder = Geocoder { q ->
            when {
                q.startsWith("Stand Fuji Ebisu") -> GeoHit(35.6467, 139.7101, null) // ~8 km from Hamamatsucho
                q.startsWith("Stand Fuji Osaka") -> GeoHit(34.69, 135.50, null) // another city
                q.startsWith("Eingang Ebisu") -> GeoHit(35.6467, 139.7101, null)
                else -> null
            }
        }
        val pois = listOf(
            PlacePoi("Stand Fuji Ebisu", PoiKinds.BRANCH),
            PlacePoi("Stand Fuji Osaka", PoiKinds.BRANCH),
            PlacePoi("Eingang Ebisu", PoiKinds.ENTRANCE),
        )
        val kept = locatePois(pois, 35.6562, 139.7551, "Stand Fuji", "Tokyo", geocoder)
        assertEquals(listOf("Stand Fuji Ebisu"), kept.map { it.name })
        assertEquals("Weitere Filiale", PoiKinds.label(PoiKinds.BRANCH))
        assertEquals(PoiKinds.BRANCH, PoiKinds.normalize("BRANCH"))
    }

    @Test fun keepsNearCoordinatesGeocodesUnknownAndDropsFarOnes() {
        val queries = mutableListOf<String>()
        val geocoder = Geocoder { q ->
            queries += q
            when {
                q.startsWith("Senbon") -> GeoHit(34.9675, 135.7790, null) // ~600 m away
                q.startsWith("Namensvetter") -> GeoHit(35.68, 139.76, null) // Tokyo: far
                else -> null
            }
        }
        val pois = listOf(
            PlacePoi("Nordtor", PoiKinds.ENTRANCE, 34.968, 135.773),
            PlacePoi("Senbon Torii", PoiKinds.VIEWPOINT),
            PlacePoi("Falsche Lage", PoiKinds.OTHER, 35.68, 139.76),
            PlacePoi("Namensvetter", PoiKinds.STATION),
        )
        val kept = locatePois(pois, anchorLat, anchorLng, "Fushimi Inari", "Kyoto", geocoder)
        assertEquals(listOf("Nordtor", "Senbon Torii"), kept.map { it.name })
        assertEquals(34.9675, kept[1].lat)
        assertTrue(queries.any { it == "Senbon Torii Fushimi Inari Kyoto" })
        // No anchor: nothing can be checked, nothing is kept.
        assertTrue(locatePois(pois, null, null, "x", null, geocoder).isEmpty())
    }
}
