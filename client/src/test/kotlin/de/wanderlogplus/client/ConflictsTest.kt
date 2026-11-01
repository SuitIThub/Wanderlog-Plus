package de.wanderlogplus.client

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class ConflictsTest {
    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.PLACE, "p1", Place("p1", name = "Tempel"))
        it.seedEntity(EntityType.SAVED_PLACE, "s1", SavedPlace("s1", "p1", note = "app"), mapOf("_conflict.note" to JsonPrimitive("wl")))
    }

    @Test fun keepAppClearsConflictKeepsValue() {
        val f = fx()
        f.editor.keepApp(f.snapshot().conflicts.single())
        val s = f.snapshot()
        assertTrue(s.conflicts.isEmpty())
        assertEquals("app", s.savedPlaces.single().note)
        assertEquals(1, f.outbox.size())
    }

    @Test fun takeWanderlogAppliesValue() {
        val f = fx()
        f.editor.takeWanderlog(f.snapshot().conflicts.single())
        val s = f.snapshot()
        assertTrue(s.conflicts.isEmpty())
        assertEquals("wl", s.savedPlaces.single().note)
        assertEquals(2, f.outbox.size())
    }
}
