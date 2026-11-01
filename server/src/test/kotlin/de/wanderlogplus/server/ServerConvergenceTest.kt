package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import de.wanderlogplus.shared.sync.applyChanges
import de.wanderlogplus.shared.sync.change
import de.wanderlogplus.shared.sync.parse
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConvergenceTest {
    @TempDir
    lateinit var dir: Path

    private var time = 1_000_000L

    private inner class Device(val author: String, val node: String, val sync: SyncService) {
        val clock = HlcClock(node) { time }
        val local = InMemoryEntityStore()
        val outbox = ArrayList<FieldChange>()
        var since = 0L

        fun edit(type: EntityType, id: String, field: String, value: JsonPrimitive) {
            val c = clock.change(type, id, field, value)
            local.applyChanges(listOf(c))
            outbox += c
        }

        fun push() {
            if (outbox.isEmpty()) return
            sync.push(author, outbox.toList())
            outbox.clear()
        }

        fun pull() {
            do {
                val page = sync.pull(since, 3)
                for (rec in page.records) {
                    for ((f, fv) in rec.fields) {
                        clock.receive(Hlc.parse(fv.hlc))
                        val base = local.get(rec.type, rec.id) ?: EntityRecord(rec.type, rec.id)
                        base.apply(FieldChange(rec.type, rec.id, f, fv.value, fv.hlc))?.let { local.put(it) }
                    }
                }
                since = page.nextSeq
            } while (page.hasMore)
        }
    }

    private fun asMap(records: List<EntityRecord>) = records.associateBy { it.type to it.id }

    @Test
    fun devicesAndServerConverge() {
        val store = SqliteStore(dir.resolve("wanderlog-plus.db"))
        try {
            val sync = SyncService(store) { time }
            val importer = HlcClock("importer") { time }
            val a = Device("alice", "dev-a", sync)
            val b = Device("bob", "dev-b", sync)
            val devices = listOf(a, b)
            val rnd = Random(42)
            val ids = (1..5).map { "place-$it" }
            val fields = listOf("notes", "title", "deleted")

            repeat(200) { round ->
                time += rnd.nextLong(0, 40)
                for (d in devices) {
                    repeat(rnd.nextInt(0, 3)) {
                        val field = fields.random(rnd)
                        val value = if (field == "deleted") JsonPrimitive(rnd.nextBoolean()) else JsonPrimitive("v${rnd.nextInt(1000)}")
                        d.edit(EntityType.PLACE, ids.random(rnd), field, value)
                    }
                    if (rnd.nextInt(3) == 0) d.push()
                    if (rnd.nextInt(3) == 0) d.pull()
                }
                if (round % 50 == 49) {
                    val id = ids.random(rnd)
                    val v = JsonPrimitive("W$round")
                    sync.withWriteLock {
                        store.transaction("importer") {
                            store.applyChanges(
                                listOf(
                                    importer.change(EntityType.PLACE, id, "notes", v),
                                    importer.change(EntityType.PLACE, id, "_base.notes", v),
                                ),
                            )
                        }
                    }
                }
            }

            repeat(2) {
                a.push(); b.push()
                a.pull(); b.pull()
            }
            val expected = asMap(store.all())
            assertEquals(expected, asMap(a.local.all()))
            assertEquals(expected, asMap(b.local.all()))
        } finally {
            store.close()
        }
    }
}
