package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.applyChanges
import de.wanderlogplus.shared.sync.encode
import de.wanderlogplus.shared.sync.parse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun hlc(wall: Long, node: String, counter: Int = 0): String = Hlc(wall, counter, node).encode()

class SyncServiceTest {
    @TempDir
    lateinit var dir: Path

    private val nowMillis = 1_000_000_000L
    private lateinit var store: SqliteStore
    private lateinit var sync: SyncService

    @BeforeEach
    fun setUp() {
        store = SqliteStore(dir.resolve("wanderlog-plus.db"))
        sync = SyncService(store) { nowMillis }
    }

    @AfterEach
    fun tearDown() = store.close()

    private fun ch(field: String, value: String, wall: Long, node: String = "dev-a", id: String = "p", counter: Int = 0) =
        FieldChange(EntityType.PLACE, id, field, JsonPrimitive(value), hlc(wall, node, counter))

    private fun field(name: String, id: String = "p") = store.get(EntityType.PLACE, id)?.fields?.get(name)
    private fun str(name: String): JsonElement? = field(name)?.value

    private fun importerWrite(vararg c: FieldChange) {
        sync.withWriteLock { store.transaction("importer") { store.applyChanges(c.toList()) } }
    }

    @Test
    fun acceptsNewerRejectsOlder() {
        assertEquals(1, sync.push("alice", listOf(ch("notes", "a", 200))).accepted)
        assertEquals(0, sync.push("alice", listOf(ch("notes", "old", 100))).accepted)
        assertEquals(JsonPrimitive("a"), str("notes"))
        assertEquals(1, sync.push("alice", listOf(ch("notes", "b", 300))).accepted)
        assertEquals(JsonPrimitive("b"), str("notes"))
    }

    @Test
    fun duplicatePushIsNoop() {
        val batch = listOf(ch("notes", "a", 200), ch("title", "t", 200))
        assertEquals(2, sync.push("alice", batch).accepted)
        val seq = store.maxSeq()
        val r = sync.push("alice", batch)
        assertEquals(0, r.accepted)
        assertEquals(seq, store.maxSeq())
        assertEquals(seq, r.serverSeq)
        assertNull(field("_conflict.notes"))
    }

    @Test
    fun duplicatePushAfterConflictIsNoop() {
        importerWrite(ch("notes", "W2", 300, "importer"), ch("_base.notes", "W2", 300, "importer"))
        val batch = listOf(ch("notes", "Geraet", 200))
        assertEquals(1, sync.push("alice", batch).accepted)
        val seq = store.maxSeq()
        assertEquals(0, sync.push("alice", batch).accepted)
        assertEquals(seq, store.maxSeq())
    }

    @Test
    fun invalidBatchWritesNothing() {
        val e = assertFailsWith<PushRejected> {
            sync.push("alice", listOf(ch("notes", "a", 200), FieldChange(EntityType.PLACE, "p", "x", JsonPrimitive("v"), "kaputt")))
        }
        assertEquals("invalid_hlc", e.code)
        assertEquals(0, store.maxSeq())
    }

    @Test
    fun rejectsEmptyIdOrField() {
        assertEquals("invalid_change", assertFailsWith<PushRejected> { sync.push("a", listOf(ch("", "v", 1))) }.code)
        assertEquals("invalid_change", assertFailsWith<PushRejected> { sync.push("a", listOf(ch("f", "v", 1, id = ""))) }.code)
    }

    @Test
    fun rejectsTooManyChanges() {
        val many = (0..5000).map { ch("f$it", "v", 1) }
        assertEquals("too_many_changes", assertFailsWith<PushRejected> { sync.push("a", many) }.code)
        assertEquals(0, store.maxSeq())
    }

    @Test
    fun rejectsFutureHlc() {
        val h = 3_600_000L
        val e = assertFailsWith<PushRejected> { sync.push("a", listOf(ch("notes", "v", nowMillis + 25 * h))) }
        assertEquals("clock_drift", e.code)
        assertEquals(1, sync.push("a", listOf(ch("notes", "v", nowMillis + 23 * h))).accepted)
    }

    @Test
    fun rejectsReservedNode() {
        for (n in listOf("importer", "server")) {
            val e = assertFailsWith<PushRejected> { sync.push("a", listOf(ch("notes", "v", 1, n))) }
            assertEquals("reserved_node", e.code)
        }
    }

    @Test
    fun rejectionMessagesDoNotEchoValues() {
        val e = assertFailsWith<PushRejected> { sync.push("a", listOf(ch("secretfield", "secretvalue", 1, "importer", id = "secretid"))) }
        val m = e.message.orEmpty()
        listOf("secretfield", "secretvalue", "secretid", "importer").forEach { assertTrue(!m.contains(it), m) }
    }

    private fun seedImporterConflictBase() {
        importerWrite(ch("notes", "W1", 100, "importer"), ch("_base.notes", "W1", 100, "importer"))
        importerWrite(ch("notes", "W2", 300, "importer"), ch("_base.notes", "W2", 300, "importer"))
    }

    @Test
    fun deviceLosingToImporterBecomesConflict() {
        seedImporterConflictBase()
        val r = sync.push("alice", listOf(ch("notes", "Geraet", 200)))
        assertEquals(1, r.accepted)
        assertEquals(JsonPrimitive("Geraet"), str("notes"))
        assertEquals(JsonPrimitive("W2"), str("_conflict.notes"))
        assertEquals(JsonPrimitive("W2"), str("_base.notes"))
        assertTrue(Hlc.parse(field("notes")!!.hlc) > Hlc.parse(hlc(300, "importer")))
    }

    @Test
    fun maxHlcReportsHighestWrittenHlc() {
        seedImporterConflictBase()
        val r = sync.push("alice", listOf(ch("notes", "Geraet", 200)))
        assertEquals(field("notes")!!.hlc, r.maxHlc)
        assertTrue(Hlc.parse(r.maxHlc!!) > Hlc.parse(hlc(200, "dev-a")))
        assertNull(sync.push("alice", listOf(ch("notes", "old", 1))).maxHlc)
    }

    @Test
    fun rejectsReservedFields() {
        for (f in listOf("_source", "_sourceId", "_base.notes")) {
            val e = assertFailsWith<PushRejected> { sync.push("a", listOf(ch(f, "v", 1))) }
            assertEquals("reserved_field", e.code, f)
        }
        for (f in listOf("_conflict.notes", "_removedInSource", "deleted", "notes")) {
            assertEquals(1, sync.push("a", listOf(ch(f, "v", 1))).accepted, f)
        }
    }

    @Test
    fun rejectsHugeHlcCounter() {
        val e = assertFailsWith<PushRejected> { sync.push("a", listOf(ch("notes", "v", 1, counter = 999_000))) }
        assertEquals("invalid_hlc", e.code)
        assertEquals(1, sync.push("a", listOf(ch("notes", "v", 1, counter = 998_999))).accepted)
    }

    @Test
    fun deviceLosingToImporterSameValueIsQuiet() {
        seedImporterConflictBase()
        val r = sync.push("alice", listOf(ch("notes", "W2", 200)))
        assertEquals(0, r.accepted)
        assertNull(field("_conflict.notes"))
        assertEquals(JsonPrimitive("W2"), str("notes"))
    }

    @Test
    fun deviceLosingToImporterOnMasterFieldIsDropped() {
        importerWrite(ch("rating", "4", 300, "importer"))
        val r = sync.push("alice", listOf(ch("rating", "1", 200)))
        assertEquals(0, r.accepted)
        assertNull(field("_conflict.rating"))
        assertEquals(JsonPrimitive("4"), str("rating"))
    }

    @Test
    fun deviceLosingToOtherDeviceIsPlainLww() {
        importerWrite(ch("_base.notes", "W1", 100, "importer"))
        sync.push("bob", listOf(ch("notes", "bobs", 300, "dev-b")))
        val r = sync.push("alice", listOf(ch("notes", "alices", 200, "dev-a")))
        assertEquals(0, r.accepted)
        assertNull(field("_conflict.notes"))
        assertEquals(JsonPrimitive("bobs"), str("notes"))
    }

    @Test
    fun editRevivesImporterDeletedRecord() {
        importerWrite(
            ch("notes", "n", 100, "importer"),
            FieldChange(EntityType.PLACE, "p", "deleted", JsonPrimitive(true), hlc(300, "importer")),
        )
        val r = sync.push("alice", listOf(ch("notes", "edit", 200)))
        assertEquals(1, r.accepted)
        assertEquals(JsonPrimitive(false), str("deleted"))
        assertEquals(JsonPrimitive(true), str("_removedInSource"))
        assertTrue(Hlc.parse(field("deleted")!!.hlc) > Hlc.parse(hlc(300, "importer")))
    }

    @Test
    fun deviceDeleteOfRecordIsNotRevived() {
        sync.push("alice", listOf(ch("notes", "n", 100)))
        val r = sync.push("alice", listOf(FieldChange(EntityType.PLACE, "p", "deleted", JsonPrimitive(true), hlc(200, "dev-a"))))
        assertEquals(1, r.accepted)
        assertEquals(JsonPrimitive(true), str("deleted"))
        assertNull(field("_removedInSource"))
    }

    @Test
    fun multipleEditsOfSameFieldKeepNewest() {
        seedImporterConflictBase()
        val r = sync.push("alice", listOf(ch("notes", "X", 200), ch("notes", "Y", 250)))
        assertEquals(1, r.accepted)
        assertEquals(JsonPrimitive("Y"), str("notes"))
        assertEquals(JsonPrimitive("W2"), str("_conflict.notes"))
    }

    @Test
    fun losingDeleteInBatchDoesNotRevive() {
        importerWrite(
            ch("notes", "n", 100, "importer"),
            FieldChange(EntityType.PLACE, "p", "deleted", JsonPrimitive(true), hlc(300, "importer")),
        )
        sync.push(
            "alice",
            listOf(ch("notes", "edit", 200), FieldChange(EntityType.PLACE, "p", "deleted", JsonPrimitive(true), hlc(250, "dev-a"))),
        )
        assertEquals(JsonPrimitive(true), str("deleted"))
        assertNull(field("_removedInSource"))
    }
}
