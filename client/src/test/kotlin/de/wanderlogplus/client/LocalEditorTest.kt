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

    @Test fun addSuggestionUsesLargestIdWhenDatesCollide() {
        val f = fx()
        f.seedEntity(EntityType.DAY, "a-day", Day("a-day", "2027-05-04"))
        f.seedEntity(EntityType.DAY, "z-day", Day("z-day", "2027-05-04"))
        f.editor.addSuggestionToDay(f.snapshot(), "2027-05-04", "s1")
        assertEquals("z-day", f.snapshot().dayItems.single().dayId)
        assertEquals("z-day", f.snapshot().queries()!!.dayFor("2027-05-04")!!.id)
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

    private class CountingTx : Transactor {
        var depth = 0
        var topLevel = 0
        override fun <T> run(block: () -> T): T {
            if (depth == 0) topLevel++
            depth++
            try { return block() } finally { depth-- }
        }
    }

    @Test fun compoundOpsAreOneTransactionAndOneNotification() {
        val f = fx()
        val tx = CountingTx()
        var notes = 0
        val ed = LocalEditor(f.store, f.outbox, f.clock, tx) { notes++; assertEquals(0, tx.depth) }
        val a = ed.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        assertEquals(1 to 1, tx.topLevel to notes)
        val b = ed.addSuggestionToDay(f.snapshot(), "2027-05-03", "s2")
        assertEquals(2 to 2, tx.topLevel to notes)
        ed.moveDayItem(f.snapshot(), b, up = true)
        assertEquals(3 to 3, tx.topLevel to notes)
        ed.removeDayItem(f.snapshot(), a)
        assertEquals(4 to 4, tx.topLevel to notes)
    }

    @Test fun addSuggestionTwiceForSamePlaceIsNoop() {
        val f = fx()
        val a = f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        val size = f.outbox.size()
        val n = f.changes
        assertEquals(a, f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1"))
        assertEquals(size, f.outbox.size())
        assertEquals(n, f.changes)
    }

    @Test fun moveWithEqualNeighbourKeysDoesNotThrow() {
        val f = fx()
        f.seedEntity(EntityType.DAY, "d", Day("d", "2027-05-03"))
        for ((id, key) in listOf("a" to "5", "b" to "5", "c" to "8")) {
            f.seedEntity(EntityType.DAY_ITEM, id, DayItem(id, "d", key, DayItemKind.NOTE, text = id))
        }
        f.editor.moveDayItem(f.snapshot(), "c", up = true)
        val order = f.snapshot().dayItemsFor("d").map { it.id }
        assertEquals(listOf("a", "c", "b"), order)
        val keys = f.snapshot().dayItemsFor("d").map { it.sortKey }
        assertEquals(keys.sorted(), keys)
        assertEquals(3, keys.toSet().size)
    }
}

class LocalEditorPlan4Test {
    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.TRIP, "t1", Trip("t1", "Reise", "2027-05-01", "2027-05-10"))
    }

    @Test fun setDayFieldsCreatesDayOnce() {
        val f = fx()
        val dayId = Ids.dayId("t1", "2027-05-03")
        f.editor.setDayFields(f.snapshot(), "2027-05-03", mapOf("title" to JsonPrimitive("Tempeltag"), "note" to JsonPrimitive("n")))
        val day = f.snapshot().days.single()
        assertEquals(dayId, day.id)
        assertEquals("2027-05-03", day.date)
        assertEquals("Tempeltag", day.title)
        val before = f.outbox.size()
        f.editor.setDayFields(f.snapshot(), "2027-05-03", mapOf("note" to JsonPrimitive("neu")))
        val snap = f.snapshot()
        assertEquals(1, snap.days.size)
        assertEquals("neu", snap.days.single().note)
        assertEquals(before + 1, f.outbox.size())
    }

    @Test fun setDayFieldsRejectsOtherFields() {
        val f = fx()
        assertFailsWith<IllegalArgumentException> {
            f.editor.setDayFields(f.snapshot(), "2027-05-03", mapOf("date" to JsonPrimitive("2027-05-04")))
        }
        assertTrue(f.snapshot().days.isEmpty())
        assertEquals(0, f.outbox.size())
    }

    @Test fun addNoteToDayAppendsNoteItem() {
        val f = fx()
        f.seedEntity(EntityType.PLACE, "p1", Place("p1", name = "Tempel"))
        f.seedEntity(EntityType.SAVED_PLACE, "s1", SavedPlace("s1", "p1", status = PlaceStatus.IDEA))
        f.editor.addSuggestionToDay(f.snapshot(), "2027-05-03", "s1")
        val id = f.editor.addNoteToDay(f.snapshot(), "2027-05-03", "Sonnencreme")
        val snap = f.snapshot()
        val items = snap.dayItemsFor(snap.days.single().id)
        assertEquals(2, items.size)
        assertEquals(id, items.last().id)
        assertEquals(DayItemKind.NOTE, items.last().kind)
        assertEquals("Sonnencreme", items.last().text)
        assertEquals(PlaceStatus.PLANNED, snap.savedPlaces.single().status)
    }

    @Test fun addAttachmentCreatesRecord() {
        val f = fx()
        val id = f.editor.addAttachment(EntityType.STAY, "st1", "h", "a.pdf", "application/pdf", 5)
        val a = f.snapshot().attachmentsFor(EntityType.STAY, "st1").single()
        assertEquals(Attachment(id, EntityType.STAY, "st1", "h", "a.pdf", "application/pdf", 5), a)
    }
}
