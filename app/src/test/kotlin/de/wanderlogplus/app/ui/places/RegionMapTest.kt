package de.wanderlogplus.app.ui.places

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionMapTest {
    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedData() {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "pa", Place("pa", name = "Alpha", lat = 35.0, lng = 135.0))
        seed(EntityType.PLACE, "pz", Place("pz", name = "Null", lat = 0.0, lng = 0.0))
        seed(EntityType.PLACE, "po", Place("po", name = "Lose", lat = 1.0, lng = 2.0))
        seed(EntityType.SAVED_PLACE, "sv-a", SavedPlace("sv-a", "pa", "r1", status = PlaceStatus.PLANNED))
        seed(EntityType.SAVED_PLACE, "sv-z", SavedPlace("sv-z", "pz", "r1"))
        seed(EntityType.SAVED_PLACE, "sv-o", SavedPlace("sv-o", "po", null))
    }

    @Test fun markersSkipZeroCoords() {
        seedData()
        val m = mapMarkers(TripSnapshot.from(store.all()), "r1")
        assertEquals(listOf(MapMarker("sv-a", "Alpha", 35.0, 135.0, PlaceStatus.PLANNED)), m)
    }

    @Test fun markersMatchListSelection() {
        seedData()
        val snap = TripSnapshot.from(store.all())
        assertEquals(listOf("sv-o"), mapMarkers(snap, NO_REGION).map { it.savedPlaceId })
        assertEquals(listOf("sv-a"), mapMarkers(snap, "r1").map { it.savedPlaceId })
    }
}
