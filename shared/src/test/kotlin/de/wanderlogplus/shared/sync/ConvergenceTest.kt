package de.wanderlogplus.shared.sync

import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvergenceTest {
    @Test fun `two devices and importer converge regardless of delivery order`() {
        val rnd = Random(42)
        var t = 1_000L
        val clocks = listOf("dev-a", "dev-b", "importer").map { n -> HlcClock(n) { t } }
        val ids = (1..5).map { "id$it" }
        val fields = listOf("title", "note", "deleted", "lat")
        val changes = clocks.flatMap { clock ->
            (1..200).map {
                t += rnd.nextInt(0, 3)
                clock.change(EntityType.PLACE, ids.random(rnd), fields.random(rnd), JsonPrimitive(rnd.nextInt(1000)))
            }
        }
        val stores = clocks.map { InMemoryEntityStore() }
        stores.forEach { it.applyChanges(changes.shuffled(Random(rnd.nextInt()))) }
        val results = stores.map { s -> s.all().sortedBy { it.id } }
        assertEquals(results[0], results[1])
        assertEquals(results[0], results[2])
        assertEquals(5, results[0].size)
    }
}
