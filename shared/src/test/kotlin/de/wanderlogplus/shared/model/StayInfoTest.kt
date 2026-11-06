package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

class StayInfoTest {
    private fun rec(type: EntityType, id: String, fields: Map<String, JsonElement>) =
        EntityRecord(type, id, fields.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) })

    private val bath = AmenityGroup("Bad", listOf(Amenity("Rotenburo", "privat")))
    private val info = StayInfo(
        room = RoomInfo(type = "Japanisches Zimmer", beds = "2 Futons", sizeSqm = 24.0, amenities = listOf(bath)),
        property = PropertyInfo(meals = "Kaiseki 18:00", amenities = listOf(AmenityGroup("Service", listOf(Amenity("Wäscherei"))))),
        sources = listOf("https://ryokan.example"), researchedAt = 5,
    )

    @Test
    fun `new fields survive the codec`() {
        val stay = Stay("st", "p", info = info, infoLinks = listOf("https://ryokan.example"))
        assertEquals(stay, EntityCodec.decode<Stay>(rec(EntityType.STAY, "st", EntityCodec.fields(stay))))
        val train = Train("tr", seats = listOf(Seat("12", "111"), Seat("12", "114", "Selina")), travelClass = "2. Klasse", seatMap = "db-ice4-2kl")
        assertEquals(train, EntityCodec.decode<Train>(rec(EntityType.TRAIN, "tr", EntityCodec.fields(train))))
        val flight = Flight("f", seats = listOf(Seat(seat = "43E")), travelClass = "Economy")
        assertEquals(flight, EntityCodec.decode<Flight>(rec(EntityType.FLIGHT, "f", EntityCodec.fields(flight))))
        val photo = Attachment("a", EntityType.STAY, "st", kind = "photo", label = Attachment.LABEL_ROOM)
        assertEquals(photo, EntityCodec.decode<Attachment>(rec(EntityType.ATTACHMENT, "a", EntityCodec.fields(photo))))
    }

    @Test
    fun `merge keeps hand edits`() {
        val old = info.copy(
            room = info.room.copy(type = "Twin (von Hand)", amenities = listOf(AmenityGroup("Bad", listOf(Amenity("Dusche"))))),
            editedByHand = setOf("room.type", "room.amenities.Bad"),
        )
        val new = StayInfo(
            room = RoomInfo(type = "Deluxe", beds = "2 Betten", amenities = listOf(bath, AmenityGroup("Technik", listOf(Amenity("WLAN"))))),
            property = PropertyInfo(description = "Ryokan am Fluss"),
            sources = listOf("https://neu.example"), researchedAt = 9,
        )
        val m = StayInfoMerge.merge(old, new)
        assertEquals("Twin (von Hand)", m.room.type)
        assertEquals("2 Betten", m.room.beds)
        assertEquals(listOf("Dusche"), m.room.amenities.first { it.name == "Bad" }.items.map { it.name })
        assertEquals(listOf("WLAN"), m.room.amenities.first { it.name == "Technik" }.items.map { it.name })
        assertEquals("Ryokan am Fluss", m.property.description)
        assertEquals(old.editedByHand, m.editedByHand)
        assertEquals(listOf("https://neu.example"), m.sources)
        assertEquals(9L, m.researchedAt)
    }

    @Test
    fun `merge without old takes new, edited adds the key once`() {
        assertEquals(info, StayInfoMerge.merge(null, info))
        val e = StayInfoMerge.edited(StayInfoMerge.edited(info, "room.type"), "room.type")
        assertEquals(setOf("room.type"), e.editedByHand)
    }
}
