package de.wanderlogplus.importer

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndToEndTest {
    private fun realJson(): String {
        val f = File(System.getProperty("repoRoot") ?: ".", "testdata/private/wanderlog-real.json")
        assumeTrue(f.exists())
        return f.readText()
    }

    @Test
    fun `real import twice is idempotent`() {
        val json = realJson()
        val store = InMemoryEntityStore()
        val clock = HlcClock("importer")
        val first = ImportRunner.run(json, store, clock)
        assertTrue(first.stats.created > 0)
        val second = ImportRunner.run(json, store, clock)
        assertTrue(second.changes.isEmpty(), "second run produced ${second.changes.size} changes")
    }

    @Test
    fun `real summary`() {
        val store = InMemoryEntityStore()
        ImportRunner.run(realJson(), store, HlcClock("importer"))
        val q = store.tripQueries()
        assertEquals(22, q.days().size)
        assertNull(q.regionFor("2027-05-08"))
        val names = store.all(EntityType.REGION).associate {
            val r = EntityCodec.decode<Region>(it); r.id to r.name
        }
        fun region(d: String) = q.regionFor("2027-$d")?.let { names[it] }
        assertEquals(
            listOf("Tokio", "Kiso", "Gero", "Osaka", "Hiroshima", "Okinawa", "Tokio"),
            listOf("05-09", "05-14", "05-16", "05-20", "05-23", "05-26", "05-29").map(::region),
        )
        val placeNames = store.all(EntityType.PLACE).associate {
            val p = EntityCodec.decode<Place>(it); p.id to p.name
        }
        val regionNames = store.all(EntityType.SAVED_PLACE).associate {
            val s = EntityCodec.decode<SavedPlace>(it); s.placeId to names[s.regionId]
        }
        val sugg = q.suggestionsFor("2027-05-20")
        val rs = sugg.mapNotNull { names[it.regionId] }.toSet()
        assertTrue("Kyoto" in rs && "Nara" in rs, "regions of suggestions: $rs")
        assertTrue(placeNames.isNotEmpty() && regionNames.isNotEmpty())
    }

    @Test
    fun `broken input leaves store untouched`() {
        val store = InMemoryEntityStore()
        val clock = HlcClock("importer")
        ImportRunner.run(realJson(), store, clock)
        val before = store.all()
        assertFailsWith<ImportFormatException> { ImportRunner.run("{}", store, clock) }
        assertEquals(before, store.all())
    }
}
