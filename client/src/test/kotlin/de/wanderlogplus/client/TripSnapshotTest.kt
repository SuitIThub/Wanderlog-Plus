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

class TripSnapshotAttachmentsTest {
    @Test fun attachmentsForFiltersDeletedAndSorts() {
        val f = EditorFixture()
        f.seedEntity(EntityType.TRIP, "t1", Trip("t1", "A"))
        f.seedEntity(EntityType.ATTACHMENT, "a2", Attachment("a2", EntityType.STAY, "s1", "h2", "b.pdf"))
        f.seedEntity(EntityType.ATTACHMENT, "a1", Attachment("a1", EntityType.STAY, "s1", "h1", "b.pdf"))
        f.seedEntity(EntityType.ATTACHMENT, "a3", Attachment("a3", EntityType.STAY, "s1", "h3", "a.pdf"))
        f.seedEntity(EntityType.ATTACHMENT, "a4", Attachment("a4", EntityType.STAY, "s2", "h4", "a.pdf"))
        f.seedEntity(EntityType.ATTACHMENT, "a5", Attachment("a5", EntityType.FLIGHT, "s1", "h5", "a.pdf"))
        f.seedEntity(EntityType.ATTACHMENT, "a6", Attachment("a6", EntityType.STAY, "s1", "h6", "0.pdf"))
        f.editor.delete(EntityType.ATTACHMENT, "a6")
        val s = f.snapshot()
        assertEquals(5, s.attachments.size)
        assertEquals(listOf("a3", "a1", "a2"), s.attachmentsFor(EntityType.STAY, "s1").map { it.id })
    }
}
