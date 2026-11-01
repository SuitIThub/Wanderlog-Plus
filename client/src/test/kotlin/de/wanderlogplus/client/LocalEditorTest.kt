package de.wanderlogplus.client

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class LocalEditorTest {
    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.TRIP, "t1", Trip("t1", "Reise", "2027-05-01", "2027-05-10"))
        it.seedEntity(EntityType.PLACE, "p1", Place("p1", name = "Tempel"))
        it.seedEntity(EntityType.PLACE, "p2", Place("p2", name = "Markt"))
        it.seedEntity(EntityType.SAVED_PLACE, "s1", SavedPlace("s1", "p1", status = PlaceStatus.IDEA))
        it.seedEntity(EntityType.SAVED_PLACE, "s2", SavedPlace("s2", "p2", status = PlaceStatus.IDEA))
    }

    @Test fun setWritesStoreOutboxAndNotifies() {
        val f = fx()
        f.editor.set(EntityType.SAVED_PLACE, "s1", mapOf("note" to JsonPrimitive("hi")))
        assertEquals(JsonPrimitive("hi"), f.store.get(EntityType.SAVED_PLACE, "s1")!!.value("note"))
        assertEquals(1, f.outbox.size())
        assertEquals(1, f.changes)
    }

    @Test fun reservedFieldsRejectedBeforeWriting() {
        val f = fx()
        for (k in listOf("_source", "_sourceId", "_base.note")) {
            assertFailsWith<IllegalArgumentException> {
                f.editor.set(EntityType.SAVED_PLACE, "s1", mapOf("note" to JsonPrimitive("x"), k to JsonPrimitive("y")))
            }
        }
        assertNotEquals(JsonPrimitive("x"), f.store.get(EntityType.SAVED_PLACE, "s1")!!.value("note"))
        assertEquals(0, f.outbox.size())
        assertEquals(0, f.changes)
    }

    @Test fun createSetsDeletedFalseAndUuidV7() {
        val f = fx()
        val id = f.editor.create(EntityType.NOTE, mapOf("title" to JsonPrimitive("N")))
        assertEquals('7', id[14])
        val rec = f.store.get(EntityType.NOTE, id)!!
        assertEquals(JsonPrimitive(false), rec.fields["deleted"]!!.value)
        assertFalse(rec.isDeleted)
    }

    @Test fun addSuggestionCreatesDayItemAndPlans() {
        val f = fx()
        val itemId = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        val snap = f.snapshot()
        val dayId = Ids.dayId("t1", "2027-05-03")
        assertEquals(listOf(dayId), snap.days.map { it.id })
        assertEquals("2027-05-03", snap.days.single().date)
        val item = snap.dayItems.single()
        assertEquals(itemId, item.id)
        assertEquals(dayId, item.dayId)
        assertEquals("p1", item.placeId)
        assertEquals(DayItemKind.PLACE, item.kind)
        assertEquals(PlaceStatus.PLANNED, snap.savedPlaces.first { it.id == "s1" }.status)
    }

    @Test fun addSuggestionSortsAfterLastAndReusesDay() {
        val f = fx()
        f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        val second = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s2")
        val snap = f.snapshot()
        assertEquals(1, snap.days.size)
        val items = snap.dayItemsFor(Ids.dayId("t1", "2027-05-03"))
        assertEquals(2, items.size)
        assertEquals(second, items.last().id)
        assertTrue(items[0].sortKey < items[1].sortKey)
    }

    @Test fun addSuggestionUsesImportedDay() {
        val f = fx()
        f.seedEntity(EntityType.DAY, "imp", Day("imp", "2027-05-04"))
        f.editor.addSuggestionToDay(f.snapshot(), "2027-05-04", "s1")
        val snap = f.snapshot()
        assertEquals(listOf("imp"), snap.days.map { it.id })
        assertEquals("imp", snap.dayItems.single().dayId)
    }

    @Test fun moveDayItemSwapsOrderAndNoopsAtEdges() {
        val f = fx()
        val a = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        val b = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s2")
        val dayId = Ids.dayId("t1", "2027-05-03")
        f.editor.moveDayItem(f.snapshot(), b, up = true)
        assertEquals(listOf(b, a), f.snapshot().dayItemsFor(dayId).map { it.id })
        val size = f.outbox.size()
        f.editor.moveDayItem(f.snapshot(), b, up = true)
        assertEquals(size, f.outbox.size())
        f.editor.moveDayItem(f.snapshot(), b, up = false)
        assertEquals(listOf(a, b), f.snapshot().dayItemsFor(dayId).map { it.id })
    }

    @Test fun removeDayItemResetsToIdeaUnlessPlannedElsewhere() {
        val f = fx()
        val a = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        f.editor.removeDayItem(f.snapshot(), a)
        var snap = f.snapshot()
        assertTrue(snap.dayItems.isEmpty())
        assertEquals(PlaceStatus.IDEA, snap.savedPlaces.first { it.id == "s1" }.status)

        val x = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        f.editor.addSuggestionToDay(f.snapshot(), "2027-05-04", "s1")
        f.editor.removeDayItem(f.snapshot(), x)
        snap = f.snapshot()
        assertEquals(1, snap.dayItems.size)
        assertEquals(PlaceStatus.PLANNED, snap.savedPlaces.first { it.id == "s1" }.status)
    }
}
