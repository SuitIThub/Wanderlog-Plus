package de.wanderlogplus.importer

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.applyChanges
import de.wanderlogplus.shared.sync.change
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReimportMergerTest {
    private var importerTime = 1_000L
    private val importer = HlcClock("importer") { importerTime }
    private val app = HlcClock("dev-a") { 9_000_000L }
    private val merger = ReimportMerger(importer)
    private val store = InMemoryEntityStore()

    private fun snapshot(): ImportSnapshot =
        WanderlogMapper.map(WanderlogParser.parse(javaClass.getResource("/wanderlog-mini.json")!!.readText()))

    private fun savedFor(s: ImportSnapshot, pid: String) = s.entities.first {
        it.type == EntityType.SAVED_PLACE &&
            (it.fields["placeId"] as? JsonPrimitive)?.content == EntityCodec.importedId(EntityType.PLACE, "place:$pid")
    }

    private fun ImportSnapshot.without(e: ImportedEntity) = ImportSnapshot(entities - e)
    private fun ImportSnapshot.withField(e: ImportedEntity, f: String, v: JsonElement) =
        ImportSnapshot(entities.map { if (it === e) it.copy(fields = it.fields + (f to v)) else it })

    private fun run(s: ImportSnapshot): ImportResult {
        importerTime += 1000
        val r = merger.merge(s, store)
        store.applyChanges(r.changes)
        return r
    }

    private fun rec(e: ImportedEntity) = store.get(e.type, e.id)!!
    private fun appSet(e: ImportedEntity, f: String, v: JsonElement) {
        store.applyChanges(listOf(app.change(e.type, e.id, f, v)))
    }

    @Test
    fun `first import creates everything`() {
        val s = snapshot()
        val r = run(s)
        assertEquals(s.entities.size, r.stats.created)
        s.entities.forEach { assertNotNull(store.get(it.type, it.id)) }
        val stay = s.entities.first { it.type == EntityType.STAY }
        EntityCodec.decode<Stay>(rec(stay))
        assertEquals(JsonPrimitive("wanderlog"), rec(stay).value("_source"))
        assertEquals(JsonPrimitive(stay.sourceId), rec(stay).value("_sourceId"))
        assertEquals(JsonPrimitive(false), rec(stay).fields["_base.deleted"]!!.value)
    }

    @Test
    fun `shifted sortKeys of existing day items are not reimported`() {
        val s = snapshot()
        run(s)
        val items = s.entities.filter { it.type == EntityType.DAY_ITEM }
        assertTrue(items.isNotEmpty())
        val before = items.associateWith { rec(it).value("sortKey") }
        val shifted = ImportSnapshot(s.entities.map {
            if (it.type == EntityType.DAY_ITEM) it.copy(fields = it.fields + ("sortKey" to JsonPrimitive("zzz-" + it.sourceId))) else it
        })
        val r = run(shifted)
        assertEquals(0, r.stats.conflicts)
        assertEquals(0, r.stats.updated)
        items.forEach {
            assertEquals(before[it], rec(it).value("sortKey"))
            assertNull(rec(it).value("_conflict.sortKey"))
        }
        assertTrue(run(shifted).changes.isEmpty())
    }

    @Test
    fun `app-changed heuristic regionId is kept without conflict`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "regionId", JsonPrimitive("app-region"))
        val r = run(s.withField(f1, "regionId", JsonPrimitive("other-heuristic")))
        assertEquals(JsonPrimitive("app-region"), rec(f1).value("regionId"))
        assertNull(rec(f1).value("_conflict.regionId"))
        assertEquals(0, r.stats.conflicts)
        assertTrue(run(s.withField(f1, "regionId", JsonPrimitive("other-heuristic"))).changes.isEmpty())
    }

    @Test
    fun `second identical import is a no-op`() {
        val s = snapshot()
        run(s)
        assertTrue(run(s).changes.isEmpty())
    }

    @Test
    fun `wanderlog change on untouched field is applied`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        val r = run(s.withField(f1, "note", JsonPrimitive("neu")))
        assertEquals(JsonPrimitive("neu"), rec(f1).value("note"))
        assertEquals(JsonPrimitive("neu"), rec(f1).value("_base.note"))
        assertEquals(1, r.stats.updated)
    }

    @Test
    fun `app edit survives reimport`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "note", JsonPrimitive("meins"))
        val r = run(s)
        assertEquals(JsonPrimitive("meins"), rec(f1).value("note"))
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun `conflict is recorded`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "note", JsonPrimitive("meins"))
        val s2 = s.withField(f1, "note", JsonPrimitive("deren"))
        val r = run(s2)
        assertEquals(JsonPrimitive("meins"), rec(f1).value("note"))
        assertEquals(JsonPrimitive("deren"), rec(f1).value("_conflict.note"))
        assertEquals(1, r.stats.conflicts)
        assertTrue(run(s2).changes.isEmpty())
    }

    @Test
    fun `same change on both sides clears conflict`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "note", JsonPrimitive("meins"))
        run(s.withField(f1, "note", JsonPrimitive("deren")))
        val s3 = s.withField(f1, "note", JsonPrimitive("meins"))
        run(s3)
        assertEquals(JsonNull, rec(f1).fields["_conflict.note"]!!.value)
        assertEquals(JsonPrimitive("meins"), rec(f1).value("note"))
        assertEquals(JsonPrimitive("meins"), rec(f1).value("_base.note"))
        assertTrue(run(s3).changes.isEmpty())
    }

    @Test
    fun `master fields always follow wanderlog`() {
        val s = snapshot()
        val place = s.entities.first { it.type == EntityType.PLACE }
        fun withMaster(v: Double) = ImportSnapshot(s.entities.map {
            if (it === place) it.copy(fields = it.fields + ("rating" to JsonPrimitive(v)), masterFields = setOf("rating")) else it
        })
        run(withMaster(4.0))
        assertFalse(rec(place).fields.containsKey("_base.rating"))
        appSet(place, "rating", JsonPrimitive(1.0))
        run(withMaster(4.5))
        assertEquals(JsonPrimitive(4.5), rec(place).value("rating"))
        assertFalse(rec(place).fields.containsKey("_base.rating"))
        assertTrue(run(withMaster(4.5)).changes.isEmpty())
    }

    @Test
    fun `removed untouched entity is soft-deleted`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        val r = run(s.without(f1))
        assertTrue(rec(f1).isDeleted)
        assertTrue(r.stats.removed >= 1)
        assertTrue(run(s.without(f1)).changes.isEmpty())
    }

    private fun refsTo(s: ImportSnapshot, place: ImportedEntity) = s.entities.filter {
        it.type in setOf(EntityType.SAVED_PLACE, EntityType.STAY, EntityType.DAY_ITEM) &&
            (it.fields["placeId"] as? JsonPrimitive)?.content == place.id
    }

    @Test
    fun `placeReferencedByAppDataIsFlaggedNotDeleted`() {
        val s = snapshot()
        run(s)
        val p = savedFor(s, "PID_F1").let { sp -> s.entities.first { it.type == EntityType.PLACE && it.id == (sp.fields["placeId"] as JsonPrimitive).content } }
        val h = "000000000000001-000000-dev-a"
        store.put(EntityRecord(EntityType.DAY_ITEM, "app-item", mapOf(
            "placeId" to FieldValue(JsonPrimitive(p.id), h), "deleted" to FieldValue(JsonPrimitive(false), h))))
        val gone = refsTo(s, p).toSet() + p
        val r = run(ImportSnapshot(s.entities.filter { it !in gone }))
        assertFalse(rec(p).isDeleted)
        assertEquals(JsonPrimitive(true), rec(p).value("_removedInSource"))
        assertTrue(r.stats.flaggedRemoved >= 1)
    }

    @Test
    fun `unreferencedPlaceIsDeleted`() {
        val s = snapshot()
        run(s)
        val p = savedFor(s, "PID_F1").let { sp -> s.entities.first { it.type == EntityType.PLACE && it.id == (sp.fields["placeId"] as JsonPrimitive).content } }
        val gone = refsTo(s, p).toSet() + p
        run(ImportSnapshot(s.entities.filter { it !in gone }))
        assertTrue(rec(p).isDeleted)
    }

    @Test
    fun `removed edited entity is flagged`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "note", JsonPrimitive("meins"))
        val r = run(s.without(f1))
        assertFalse(rec(f1).isDeleted)
        assertEquals(JsonPrimitive(true), rec(f1).value("_removedInSource"))
        assertEquals(1, r.stats.flaggedRemoved)
        assertTrue(run(s.without(f1)).changes.isEmpty())
        run(s)
        assertEquals(JsonPrimitive(false), rec(f1).value("_removedInSource"))
        assertTrue(run(s).changes.isEmpty())
    }

    @Test
    fun `reappearing entity is restored`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        run(s.without(f1))
        assertTrue(rec(f1).isDeleted)
        run(s)
        assertFalse(rec(f1).isDeleted)
        assertTrue(run(s).changes.isEmpty())
    }

    @Test
    fun `user deletion survives reimport`() {
        val s = snapshot()
        run(s)
        val t2 = savedFor(s, "PID_T2")
        appSet(t2, "deleted", JsonPrimitive(true))
        val r = run(s)
        assertTrue(rec(t2).isDeleted)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun `app-created entities are untouched`() {
        val s = snapshot()
        run(s)
        val h = "000000000000001-000000-dev-a"
        val appRec = EntityRecord(
            EntityType.SAVED_PLACE, "app-1",
            mapOf("_source" to FieldValue(JsonPrimitive("app"), h), "note" to FieldValue(JsonPrimitive("x"), h)),
        )
        store.put(appRec)
        val r = run(s)
        assertEquals(appRec, store.get(EntityType.SAVED_PLACE, "app-1"))
        assertTrue(r.changes.isEmpty())
        EntityCodec.decode<SavedPlace>(appRec)
    }

    @Test
    fun `wanderlog change is applied although app clock is ahead`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "note", rec(f1).fields["_base.note"]!!.value)
        val s2 = s.withField(f1, "note", JsonPrimitive("neu"))
        run(s2)
        assertEquals(JsonPrimitive("neu"), rec(f1).value("note"))
        assertEquals(JsonPrimitive("neu"), rec(f1).value("_base.note"))
        assertTrue(run(s2).changes.isEmpty())
    }

    @Test
    fun `new conflict is recorded after app resolved the previous one with a newer clock`() {
        val s = snapshot()
        run(s)
        val f1 = savedFor(s, "PID_F1")
        appSet(f1, "note", JsonPrimitive("meins"))
        run(s.withField(f1, "note", JsonPrimitive("deren")))
        appSet(f1, "_conflict.note", JsonNull)
        appSet(f1, "note", JsonPrimitive("meins2"))
        val s3 = s.withField(f1, "note", JsonPrimitive("nochmal"))
        run(s3)
        assertEquals(JsonPrimitive("nochmal"), rec(f1).value("_conflict.note"))
        assertEquals(JsonPrimitive("meins2"), rec(f1).value("note"))
        assertTrue(run(s3).changes.isEmpty())
    }
}
