package de.wanderlogplus.client

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class TripSnapshotTest {
    @Test fun emptyRecords() {
        val s = TripSnapshot.from(emptyList())
        assertTrue(s.isEmpty)
        assertNull(s.queries())
    }

    @Test fun deletedRecordsAreMissing() {
        val f = EditorFixture()
        f.seedEntity(EntityType.TRIP, "t1", Trip("t1", "A"))
        f.seedEntity(EntityType.PLACE, "p1", Place("p1", name = "X"))
        f.editor.delete(EntityType.PLACE, "p1")
        val s = f.snapshot()
        assertFalse(s.isEmpty)
        assertNotNull(s.queries())
        assertTrue(s.places.isEmpty())
    }

    @Test fun oddRecordIsSkipped() {
        val f = EditorFixture()
        f.seedEntity(EntityType.TRIP, "t1", Trip("t1", "A"))
        f.seed(EntityType.STAY, "bad", mapOf("adults" to JsonPrimitive("many"), "deleted" to JsonPrimitive(false)))
        assertTrue(f.snapshot().stays.isEmpty())
    }

    @Test fun conflictFoundWithLabel() {
        val f = EditorFixture()
        f.seedEntity(EntityType.PLACE, "p1", Place("p1", name = "Tempel"))
        f.seedEntity(
            EntityType.SAVED_PLACE, "s1", SavedPlace("s1", "p1", note = "app"),
            mapOf("_conflict.note" to JsonPrimitive("wl"), "_conflict.tags" to JsonNull),
        )
        val c = f.snapshot().conflicts.single()
        assertEquals("note", c.field)
        assertEquals(JsonPrimitive("app"), c.appValue)
        assertEquals(JsonPrimitive("wl"), c.wanderlogValue)
        assertEquals("Tempel", c.label)
    }
}
