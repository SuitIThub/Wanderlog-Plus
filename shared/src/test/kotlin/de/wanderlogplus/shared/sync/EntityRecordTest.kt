package de.wanderlogplus.shared.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityRecordTest {
    private fun h(w: Long, node: String = "a") = Hlc(w, 0, node)
    private fun ch(field: String, v: String, w: Long, node: String = "a") =
        FieldChange(EntityType.PLACE, "p1", field, JsonPrimitive(v), h(w, node).encode())
    private fun rec() = EntityRecord(EntityType.PLACE, "p1")

    @Test fun `newer change wins`() {
        val r = rec().apply(ch("title", "A", 1))!!.apply(ch("title", "B", 2))!!
        assertEquals(JsonPrimitive("B"), r.value("title"))
    }

    @Test fun `older change is rejected`() {
        val r = rec().apply(ch("title", "B", 2))!!
        assertNull(r.apply(ch("title", "A", 1)))
        assertEquals(JsonPrimitive("B"), r.value("title"))
    }

    @Test fun `identical change applied twice is rejected`() {
        val c = ch("title", "A", 1)
        assertNull(rec().apply(c)!!.apply(c))
    }

    @Test fun `fields merge independently`() {
        val r = rec().apply(ch("title", "T", 1, "a"))!!.apply(ch("note", "N", 1, "b"))!!
        assertEquals(JsonPrimitive("T"), r.value("title"))
        assertEquals(JsonPrimitive("N"), r.value("note"))
    }

    @Test fun `isDeleted`() {
        assertFalse(rec().isDeleted)
        val r = rec().apply(FieldChange(EntityType.PLACE, "p1", "deleted", JsonPrimitive(true), h(1).encode()))!!
        assertTrue(r.isDeleted)
    }

    @Test fun `applyChanges returns only accepted changes`() {
        val store = InMemoryEntityStore()
        store.applyChanges(listOf(ch("title", "B", 5)))
        val accepted = store.applyChanges(listOf(ch("title", "A", 1), ch("note", "N", 1)))
        assertEquals(1, accepted.size)
        assertEquals("note", accepted[0].field)
        assertEquals(JsonPrimitive("B"), store.get(EntityType.PLACE, "p1")!!.value("title"))
    }
}
