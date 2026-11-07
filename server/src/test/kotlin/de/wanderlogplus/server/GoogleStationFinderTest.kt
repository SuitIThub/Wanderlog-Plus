package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStationNear
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleStationFinderTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-gstations").resolve("t.db"))
    private val calls = mutableListOf<Pair<String, String>>()

    private fun place(id: String, name: String, lat: Double, lng: Double, vararg types: String) =
        """{"id":"$id","displayName":{"text":"$name"},"location":{"latitude":$lat,"longitude":$lng},"types":[${types.joinToString(",") { "\"$it\"" }}]}"""

    /** Answers by path and language: Japanese and English names of the same places. */
    private var answer: (String, String) -> Pair<Int, String> = { path, body ->
        val en = "\"en\"" in body
        when {
            path.endsWith("searchNearby") -> 200 to """{"places":[
                ${place("g1", if (en) "Gion-Shijō Station" else "祇園四条駅", 35.0036, 135.7722, "train_station", "transit_station")},
                ${place("g2", if (en) "Kyoto Station" else "京都駅", 34.9858, 135.7588, "train_station")},
                ${place("g3", if (en) "Bus stop" else "バス停", 35.0040, 135.7790, "bus_station", "transit_station")}]}"""
            else -> 200 to """{"places":[${place("g9", if (en) "Nakatsugawa Station" else "中津川駅", 35.4987, 137.5026, "train_station")}]}"""
        }
    }

    private var fallbackUsed = 0
    private val fallback = object : StationFinder {
        override fun near(lat: Double, lng: Double, limit: Int): List<TransitStationNear> { fallbackUsed++; return listOf(TransitStationNear(TransitStation("ほか", "Other", lat, lng), 0)) }
        override fun locate(nameJa: String, lat: Double?, lng: Double?): TransitStation? { fallbackUsed++; return TransitStation(nameJa, "Other") }
    }

    private fun finder() = GoogleStationFinder({ path, body, _ -> calls += path to body; answer(path, body) }, store, fallback)

    @Test
    fun nearListsStationsWithJapaneseAndLatinNames() {
        val list = finder().near(35.0037, 135.7788)
        assertEquals(listOf("祇園四条", "京都"), list.map { it.station.nameJa })
        assertEquals("Gion-Shijō", list.first().station.name)
        assertTrue(list.first().meters in 500..700, list.first().meters.toString())
        val body = Json.parseToJsonElement(calls.first().second).jsonObject
        assertEquals(listOf("train_station", "subway_station", "light_rail_station"), (body["includedTypes"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("DISTANCE", body["rankPreference"]!!.jsonPrimitive.content)
        assertEquals(3000.0, body["locationRestriction"]!!.jsonObject["circle"]!!.jsonObject["radius"]!!.jsonPrimitive.double)
        assertEquals(0, fallbackUsed)
    }

    @Test
    fun nearIsCachedForTheSamePoint() {
        finder().near(35.0037, 135.7788)
        val before = calls.size
        finder().near(35.00371, 135.77881)
        assertEquals(before, calls.size)
    }

    @Test
    fun widensTheRadiusWhenNothingIsClose() {
        var round = 0
        answer = { path, body -> if ("3000.0" in body) 200 to """{"places":[]}""" else { round++; 200 to """{"places":[${place("g9", "中津川駅", 35.4987, 137.5026, "train_station")}]}""" } }
        val list = finder().near(35.45, 137.45)
        assertEquals("中津川", list.single().station.nameJa)
        assertTrue(calls.any { "10000.0" in it.second })
    }

    @Test
    fun googleFailureUsesFallback() {
        answer = { _, _ -> 500 to "" }
        assertEquals("ほか", finder().near(35.0, 135.7).single().station.nameJa)
        answer = { _, _ -> 200 to """{"places":[]}""" }
        assertEquals("ほか", finder().near(36.0, 136.7).single().station.nameJa)
    }

    @Test
    fun locateFindsTheStationByName() {
        val s = finder().locate("中津川", 35.49, 137.5)!!
        assertEquals("中津川", s.nameJa)
        assertEquals("Nakatsugawa", s.name)
        assertEquals(35.4987, s.lat)
        assertTrue(calls.first().first.endsWith("searchText"))
        assertTrue("中津川駅" in calls.first().second)
    }

    @Test
    fun locateRejectsFarAwayNamesakes() {
        answer = { _, body -> 200 to """{"places":[${place("g5", if ("\"en\"" in body) "Fuchu Station" else "府中駅", 35.67, 139.47, "train_station")}]}""" }
        assertNull(finder().locate("府中", 34.57, 133.24).takeIf { fallbackUsed == 0 })
    }

    @Test
    fun stationNames() {
        assertEquals("京都", GoogleStationFinder.nameJa("京都駅"))
        assertEquals("祇園四条", GoogleStationFinder.nameJa("祇園四条駅"))
        assertEquals("四条", GoogleStationFinder.nameJa("四条駅（京都市営）"))
        assertEquals("Kyoto", GoogleStationFinder.nameLatin("Kyoto Station"))
        assertEquals("Shijō", GoogleStationFinder.nameLatin("Shijō Station (Kyoto Municipal Subway)"))
    }
}
