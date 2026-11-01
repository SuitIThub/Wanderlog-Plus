package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EntityCodecTest {
    private val hlc = Hlc(1, 0, "a").encode()

    private fun record(type: EntityType, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) =
        EntityRecord(type, id, fields.mapValues { FieldValue(it.value, hlc) })

    @Test fun `roundtrip stay`() {
        val stay = Stay(
            id = "s1", placeId = "p1", regionId = "r1", checkIn = "2027-05-09", checkOut = "2027-05-12",
            confirmationNumber = "ABC123", travelerNames = listOf("Dan", "Eva"), adults = 2, rooms = 1,
            cancellationDeadline = "2027-05-01", cancellationPolicy = "free", note = "late arrival",
        )
        val fields = EntityCodec.fields(stay)
        assertFalse("id" in fields)
        val decoded = EntityCodec.decode<Stay>(record(EntityType.STAY, "s1", fields))
        assertEquals(stay, decoded)
    }

    @Test fun `roundtrip flight with nested endpoints`() {
        val f = Flight(
            id = "f1", airline = "LH", number = "123",
            depart = Endpoint("Berlin", "BER", date = "2027-05-09", time = "08:30"),
            arrive = Endpoint("Rome", "FCO"),
        )
        val fields = EntityCodec.fields(f)
        assertTrue(fields.getValue("depart") is kotlinx.serialization.json.JsonObject)
        assertEquals(f, EntityCodec.decode<Flight>(record(EntityType.FLIGHT, "f1", fields)))
    }

    @Test fun `decode ignores reserved fields`() {
        val r = record(
            EntityType.TRIP, "t1",
            mapOf(
                "title" to JsonPrimitive("Italien"),
                "_base.title" to JsonPrimitive("Old"),
                "_conflict.title" to JsonPrimitive("Other"),
                "_source" to JsonPrimitive("wanderlog"),
                "deleted" to JsonPrimitive(false),
            ),
        )
        val t = EntityCodec.decode<Trip>(r)
        assertEquals("t1", t.id)
        assertEquals("Italien", t.title)
    }

    @Test fun `decode tolerates missing optional fields`() {
        val r = record(EntityType.SAVED_PLACE, "sp1", mapOf("placeId" to JsonPrimitive("p1")))
        val sp = EntityCodec.decode<SavedPlace>(r)
        assertEquals("p1", sp.placeId)
        assertEquals(PlaceStatus.IDEA, sp.status)
        assertEquals(PlaceCategory.SIGHT, sp.category)
        assertEquals("", sp.note)
    }

    @Test fun `importedId is deterministic and type-specific`() {
        assertEquals(EntityCodec.importedId(EntityType.PLACE, "x"), EntityCodec.importedId(EntityType.PLACE, "x"))
        assertNotEquals(
            EntityCodec.importedId(EntityType.PLACE, "x"),
            EntityCodec.importedId(EntityType.SAVED_PLACE, "x"),
        )
    }
}
