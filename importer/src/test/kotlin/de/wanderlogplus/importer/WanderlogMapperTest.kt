package de.wanderlogplus.importer

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WanderlogMapperTest {
    private fun snapshot(): ImportSnapshot =
        WanderlogMapper.map(WanderlogParser.parse(javaClass.getResource("/wanderlog-mini.json")!!.readText()))

    private fun ImportSnapshot.of(type: EntityType) = entities.filter { it.type == type }
    private fun ImportedEntity.str(name: String): String? = (fields[name] as? JsonPrimitive)?.contentOrNull
    private fun id(type: EntityType, source: String) = EntityCodec.importedId(type, source)

    private fun ImportSnapshot.region(name: String) = of(EntityType.REGION).first { it.str("name") == name }

    @Test
    fun `creates regions with day trip link`() {
        val s = snapshot()
        assertEquals(3, s.of(EntityType.REGION).size)
        assertEquals(s.region("Osaka").id, s.region("Kyoto").str("dayTripFromRegionId"))
        assertEquals("#000000", s.region("Tokio").str("color"))
    }

    @Test
    fun `deduplicates places across lists`() {
        val s = snapshot()
        val pid = id(EntityType.PLACE, "place:PID_T1")
        assertEquals(1, s.of(EntityType.PLACE).count { it.id == pid })
        val saved = s.of(EntityType.SAVED_PLACE).filter { it.str("placeId") == pid }
        assertEquals(setOf("SIGHT", "FOOD"), saved.map { it.str("category") }.toSet())
        assertEquals(2, saved.size)
        val place = s.of(EntityType.PLACE).first { it.id == pid }
        assertEquals(place.fields.keys, place.masterFields)
        assertEquals("A test shrine", place.str("description"))
    }

    @Test
    fun `stay regions by proximity`() {
        val s = snapshot()
        fun regionOf(block: Long) = s.of(EntityType.STAY).first { it.sourceId == "block:$block" }.str("regionId")
        assertEquals(s.region("Tokio").id, regionOf(101))
        assertEquals(s.region("Osaka").id, regionOf(102))
        assertEquals(2, s.of(EntityType.SAVED_PLACE).count { it.str("placeId") == id(EntityType.PLACE, "place:PID_T1") })
        assertNull(s.of(EntityType.SAVED_PLACE).firstOrNull { it.sourceId == "block:101" })
    }

    @Test
    fun `food spot near tokyo gets tokyo region`() {
        val s = snapshot()
        val f1 = s.of(EntityType.SAVED_PLACE).first { it.str("placeId") == id(EntityType.PLACE, "place:PID_F1") }
        assertEquals(s.region("Tokio").id, f1.str("regionId"))
        assertEquals("FOOD", f1.str("category"))
    }

    @Test
    fun `day plan place is planned in day region`() {
        val s = snapshot()
        val sp = s.of(EntityType.SAVED_PLACE).first { it.sourceId == "block:115" }
        assertEquals("PLANNED", sp.str("status"))
        assertEquals(s.region("Tokio").id, sp.str("regionId"))
        val items = s.of(EntityType.DAY_ITEM).filter { it.str("dayId") == id(EntityType.DAY, "section:9") }
            .sortedBy { it.str("sortKey") }
        assertEquals(listOf("NOTE", "PLACE"), items.map { it.str("kind") })
        assertEquals("Day note", items[0].str("text"))
        assertEquals(id(EntityType.PLACE, "place:PID_T2"), items[1].str("placeId"))
        val day = s.of(EntityType.DAY).single()
        assertEquals("2027-05-10", day.str("date"))
        assertEquals("Tokio Tag", day.str("title"))
    }

    @Test
    fun `missing metadata and text are tolerated`() {
        val s = snapshot()
        val t2 = s.of(EntityType.PLACE).first { it.sourceId == "place:PID_T2" }
        assertNull(t2.str("description"))
        assertEquals(JsonArray(emptyList()), t2.fields["openingPeriods"])
        val sp = s.of(EntityType.SAVED_PLACE).first { it.sourceId == "block:106" }
        assertEquals("", sp.str("note"))
        val t1 = s.of(EntityType.PLACE).first { it.sourceId == "place:PID_T1" }
        assertEquals(2, (t1.fields["openingPeriods"] as JsonArray).size)
    }

    @Test
    fun `reactions become tags`() {
        val s = snapshot()
        val sp = s.of(EntityType.SAVED_PLACE).first { it.sourceId == "block:105" }
        assertEquals(JsonArray(listOf(JsonPrimitive("🕖"))), sp.fields["tags"])
    }

    @Test
    fun `notes section`() {
        val s = snapshot()
        val note = s.of(EntityType.NOTE).first { it.str("title") == "Useful Apps" }
        assertTrue(note.str("body")!!.contains("App one") && note.str("body")!!.contains("App two"))
        assertEquals("App one\n\nApp two", note.str("body"))
        assertNotNull(s.of(EntityType.NOTE).firstOrNull { it.str("title") == "Tokio Sehenswürdigkeiten" })
    }

    @Test
    fun `transport and trip`() {
        val s = snapshot()
        val f = s.of(EntityType.FLIGHT).single()
        assertEquals("1", f.str("number"))
        assertEquals("Test Air", f.str("airline"))
        assertEquals("TA", f.str("airlineCode"))
        val t = s.of(EntityType.TRAIN).single()
        assertEquals("Take the fast train from A to B", t.str("note"))
        assertEquals("mini", s.of(EntityType.TRIP).single().str("wanderlogKey"))
        val expenses = s.of(EntityType.EXPENSE).associateBy { it.sourceId }
        assertEquals(id(EntityType.STAY, "block:101"), expenses.getValue("expense:1").str("relatedId"))
        assertEquals(id(EntityType.FLIGHT, "block:103"), expenses.getValue("expense:2").str("relatedId"))
    }

    @Test
    fun `ids are stable`() {
        assertEquals(snapshot(), snapshot())
        val s = snapshot()
        assertEquals(s.entities.size, s.entities.map { it.id }.toSet().size)
    }

    @Test
    fun `real export`() {
        val f = File(System.getProperty("repoRoot") ?: ".", "testdata/private/wanderlog-real.json")
        assumeTrue(f.exists())
        val s = WanderlogMapper.map(WanderlogParser.parse(f.readText()))
        val regions = s.of(EntityType.REGION)
        assertEquals(
            setOf("Tokio", "Kiso", "Gero", "Osaka", "Kyoto", "Nara", "Hiroshima", "Okinawa"),
            regions.map { it.str("name") }.toSet(),
        )
        val names = regions.associate { it.id to it.str("name") }
        val stays = s.of(EntityType.STAY).sortedBy { it.str("checkIn") }
        val actual = stays.map { names[it.str("regionId")] }
        assertEquals(7, stays.size)
        assertEquals(listOf("Tokio", "Kiso", "Gero", "Osaka", "Hiroshima", "Okinawa", "Tokio"), actual)
        assertEquals(6, s.of(EntityType.FLIGHT).size)
        assertEquals(10, s.of(EntityType.TRAIN).size)
        assertEquals(12, s.of(EntityType.DAY).size)
    }
}
