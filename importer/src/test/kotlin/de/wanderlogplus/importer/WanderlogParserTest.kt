package de.wanderlogplus.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WanderlogParserTest {
    private fun fixture(): String =
        javaClass.getResource("/wanderlog-mini.json")!!.readText()

    private fun fixtureObject(): JsonObject = Json.parseToJsonElement(fixture()) as JsonObject

    @Test
    fun `parses mini fixture`() {
        val r = WanderlogParser.parse(fixture())
        assertTrue(r.success)
        assertEquals(9, r.tripPlan.itinerary.sections.size)
        assertEquals("mini", r.tripPlan.key)
        assertEquals("2027-05-08", r.tripPlan.startDate)
        assertEquals("2027-05-12", r.tripPlan.endDate)
        val train = r.tripPlan.itinerary.sections.first { it.type == "transit" }.blocks.single()
        assertNull(train.carrier)
        val flight = r.tripPlan.itinerary.sections.first { it.type == "flights" }.blocks.single()
        assertEquals("FRA", flight.depart!!.airport!!.iata)
        assertEquals("TA", flight.flightInfo!!.airline!!.iata)
        val hotel = r.tripPlan.itinerary.sections.first { it.type == "hotels" }.blocks.first()
        assertEquals(35.67, hotel.place!!.geometry.location.lat)
        assertEquals(1, r.tripPlan.itinerary.budget!!.expenses.size)
        assertEquals(2, r.resources.placeMetadata.size)
        assertNull(r.resources.placeMetadata[0].openingPeriods[1].close)
    }

    @Test
    fun `rejects success false`() {
        assertFailsWith<ImportFormatException> { WanderlogParser.parse("""{"success":false}""") }
    }

    @Test
    fun `rejects missing trip dates`() {
        val obj = fixtureObject()
        val trip = obj["tripPlan"] as JsonObject
        val broken = JsonObject(obj + ("tripPlan" to JsonObject(trip - "startDate")))
        val ex = assertFailsWith<ImportFormatException> { WanderlogParser.parse(broken.toString()) }
        assertTrue(ex.message!!.contains("startDate"), ex.message)
    }

    @Test
    fun `rejects place block without place`() {
        val obj = fixtureObject()
        val trip = obj["tripPlan"] as JsonObject
        val it = trip["itinerary"] as JsonObject
        val secs = it["sections"] as JsonArray
        val sec = secs[3] as JsonObject
        val blocks = sec["blocks"] as JsonArray
        val b0 = blocks[0] as JsonObject
        val newSec = JsonObject(sec + ("blocks" to JsonArray(listOf(JsonObject(b0 - "place")) + blocks.drop(1))))
        val newSecs = JsonArray(secs.mapIndexed { i, s -> if (i == 3) newSec else s })
        val broken = JsonObject(obj + ("tripPlan" to JsonObject(trip + ("itinerary" to JsonObject(it + ("sections" to newSecs))))))
        assertFailsWith<ImportFormatException> { WanderlogParser.parse(broken.toString()) }
    }

    @Test
    fun `rejects invalid json`() {
        assertFailsWith<ImportFormatException> { WanderlogParser.parse("<html>") }
    }

    @Test
    fun `real export parses`() {
        val f = File(System.getProperty("repoRoot") ?: ".", "testdata/private/wanderlog-rkqpffsjnb.json")
        assumeTrue(f.exists())
        val r = WanderlogParser.parse(f.readText())
        assertEquals(30, r.tripPlan.itinerary.sections.size)
        assertEquals("2027-05-08", r.tripPlan.startDate)
    }
}
