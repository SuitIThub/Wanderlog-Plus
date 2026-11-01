package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.applyChanges
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteStoreTest {
    @TempDir
    lateinit var dir: Path

    private fun open() = SqliteStore(dir.resolve("test.db"))
    private fun h(ms: Long) = Hlc(ms, 0, "dev").encode()
    private fun rec(id: String, name: String = "n-$id", ms: Long = 1000) = EntityRecord(
        EntityType.PLACE, id,
        mapOf("name" to FieldValue(JsonPrimitive(name), h(ms)), "rating" to FieldValue(JsonPrimitive(4.5), h(ms))),
    )

    @Test
    fun epochStableAcrossReopenAndDiffersForNewFile() {
        val first = open().use { it.epoch }
        assertEquals(first, open().use { it.epoch })
        val other = SqliteStore(dir.resolve("other.db")).use { it.epoch }
        assertTrue(first != other)
    }

    @Test
    fun putAndGetRoundTrip() {
        val r = EntityRecord(
            EntityType.PLACE, "p1",
            mapOf(
                "name" to FieldValue(JsonPrimitive("妻籠 ⚠️"), h(1)),
                "rating" to FieldValue(JsonPrimitive(4.5), h(2)),
                "deleted" to FieldValue(JsonPrimitive(false), h(3)),
                "notes" to FieldValue(JsonNull, h(4)),
            ),
        )
        open().use { it.put(r) }
        open().use {
            assertEquals(r, it.get(EntityType.PLACE, "p1"))
            assertEquals(listOf(r), it.all())
            assertEquals(listOf(r), it.all(EntityType.PLACE))
            assertTrue(it.all(EntityType.TRIP).isEmpty())
            assertNull(it.get(EntityType.PLACE, "nope"))
        }
    }

    @Test
    fun seqIncreasesPerWrittenField() {
        open().use { s ->
            assertEquals(0, s.maxSeq())
            s.put(rec("a"))
            assertEquals(2, s.maxSeq())
            s.put(rec("a"))
            assertEquals(2, s.maxSeq())
            s.put(rec("a", name = "changed", ms = 2000).let { r ->
                r.copy(fields = r.fields + ("rating" to FieldValue(JsonPrimitive(4.5), h(1000))))
            })
            assertEquals(3, s.maxSeq())
        }
        open().use { assertEquals(3, it.maxSeq()) }
    }

    @Test
    fun applyChangesWorksThroughStore() {
        open().use { s ->
            val first = FieldChange(EntityType.PLACE, "p", "name", JsonPrimitive("new"), h(2000))
            assertEquals(listOf(first), s.applyChanges(listOf(first)))
            val old = FieldChange(EntityType.PLACE, "p", "name", JsonPrimitive("old"), h(1000))
            assertTrue(s.applyChanges(listOf(old)).isEmpty())
            assertEquals(JsonPrimitive("new"), s.get(EntityType.PLACE, "p")!!.value("name"))
            val newer = FieldChange(EntityType.PLACE, "p", "name", JsonPrimitive("newest"), h(3000))
            assertEquals(1, s.applyChanges(listOf(newer)).size)
            assertEquals(JsonPrimitive("newest"), s.get(EntityType.PLACE, "p")!!.value("name"))
        }
    }

    @Test
    fun pullPagination() {
        open().use { s ->
            val ids = (1..7).map { "p$it" }
            ids.forEach { s.put(rec(it)) }
            val p1 = s.pull(0, 3)
            assertEquals(3, p1.records.size); assertTrue(p1.hasMore)
            val p2 = s.pull(p1.nextSeq, 3)
            assertEquals(3, p2.records.size); assertTrue(p2.hasMore)
            val p3 = s.pull(p2.nextSeq, 3)
            assertEquals(1, p3.records.size); assertTrue(!p3.hasMore)
            assertEquals(ids, (p1.records + p2.records + p3.records).map { it.id })
            assertTrue((p1.records + p2.records + p3.records).all { it.fields.size == 2 })
            assertEquals(s.maxSeq(), p3.nextSeq)

            val empty = s.pull(p3.nextSeq, 3)
            assertTrue(empty.records.isEmpty()); assertEquals(p3.nextSeq, empty.nextSeq); assertTrue(!empty.hasMore)

            s.put(rec("p2", name = "changed", ms = 5000).let { r ->
                r.copy(fields = r.fields + ("rating" to FieldValue(JsonPrimitive(4.5), h(1000))))
            })
            val p4 = s.pull(p3.nextSeq, 3)
            assertEquals(listOf("p2"), p4.records.map { it.id })
            assertEquals(2, p4.records[0].fields.size)
            assertEquals(JsonPrimitive("changed"), p4.records[0].value("name"))
        }
    }

    @Test
    fun transactionRollsBack() {
        open().use { s ->
            s.put(rec("keep"))
            val before = s.maxSeq()
            assertFailsWith<IllegalStateException> {
                s.transaction("bob") {
                    s.put(rec("x"))
                    check(false) { "boom" }
                }
            }
            assertNull(s.get(EntityType.PLACE, "x"))
            assertEquals(before, s.maxSeq())
            s.put(rec("y"))
            assertEquals(before + 2, s.maxSeq())
            assertEquals("server", s.currentAuthor)
        }
    }

    @Test
    fun authorIsStored() {
        open().use { s ->
            s.put(rec("a"))
            s.transaction("bob") { s.put(rec("b")) }
            assertEquals("server", s.authorOf(EntityType.PLACE, "a", "name"))
            assertEquals("bob", s.authorOf(EntityType.PLACE, "b", "name"))
            assertEquals("server", s.currentAuthor)
        }
    }

    @Test
    fun importLogNewestFirst() {
        open().use { s ->
            assertTrue(s.importLog(10).isEmpty())
            val e1 = ImportLogEntry("2026-01-01T00:00:00Z", "2026-01-01T00:00:05Z", true, created = 3, updated = 1)
            val e2 = ImportLogEntry("2026-01-02T00:00:00Z", "2026-01-02T00:00:05Z", false, error = "x")
            s.appendImportLog(e1); s.appendImportLog(e2)
            assertEquals(listOf(e2, e1), s.importLog(10))
            assertEquals(listOf(e2), s.importLog(1))
        }
    }
}
