package de.wanderlogplus.client

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import kotlin.test.*

class DayPlanTest {
    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.TRIP, "t1", Trip("t1", "Reise", "2027-05-01", "2027-05-10"))
        it.seedEntity(EntityType.PLACE, "p1", Place("p1", name = "Tempel"))
        it.seedEntity(EntityType.PLACE, "p2", Place("p2", name = "Markt"))
        it.seedEntity(EntityType.PLACE, "p3", Place("p3", name = "Garten"))
        it.seedEntity(EntityType.SAVED_PLACE, "s1", SavedPlace("s1", "p1", status = PlaceStatus.IDEA))
        it.seedEntity(EntityType.SAVED_PLACE, "s2", SavedPlace("s2", "p2", status = PlaceStatus.IDEA))
        it.seedEntity(EntityType.SAVED_PLACE, "s3", SavedPlace("s3", "p3", status = PlaceStatus.IDEA))
    }

    private val date = "2027-05-03"
    private val dayId = Ids.dayId("t1", "2027-05-03")

    @Test fun applyReordersAddsRemovesSetsTimesAndRestoreUndoesIt() {
        val f = fx()
        val temple = f.editor.addSuggestionToDay(f.snapshot(), date, "s1")
        val note = f.editor.addNoteToDay(f.snapshot(), date, "Tickets kaufen")
        val market = f.editor.addSuggestionToDay(f.snapshot(), date, "s2")

        val at = f.editor.applyDayPlan(
            f.snapshot(), date,
            ops = listOf(
                PlanOp.AddSaved("s3", time = "08:30"),
                PlanOp.Keep(temple, time = "10:00"),
                PlanOp.AddFound("Onsen am Fluss", 35.0, 135.7, "Kiso", PlaceCategory.SIGHT, time = "17:00", note = "Gefunden im Reise-Chat"),
                PlanOp.AddSaved("s1"), // already in the day: not twice
            ),
            remove = setOf(market),
            label = "Vor Chat-Plan", now = 1000,
        )
        assertEquals(1000, at)
        val snap = f.snapshot()
        val items = snap.dayItemsFor(dayId)
        val names = items.map { i -> if (i.kind == DayItemKind.NOTE) "Notiz" else snap.places[i.placeId]?.name }
        // The note was not in the plan: it stays, after the plan.
        assertEquals(listOf("Garten", "Tempel", "Onsen am Fluss", "Notiz"), names)
        assertEquals(listOf("08:30", "10:00", "17:00", null), items.map { it.time })
        assertEquals(PlaceStatus.PLANNED, snap.savedPlaces.first { it.id == "s3" }.status)
        assertEquals(PlaceStatus.IDEA, snap.savedPlaces.first { it.id == "s2" }.status, "removed: back to an idea")
        val found = snap.savedPlaces.first { snap.places[it.placeId]?.name == "Onsen am Fluss" }
        assertEquals(PlaceStatus.PLANNED, found.status)
        assertEquals("Gefunden im Reise-Chat", found.note)
        val history = snap.queries()!!.dayFor(date)!!.history
        assertEquals(listOf("Vor Chat-Plan"), history.map { it.label })
        assertEquals(listOf(temple, note, market), history.single().items.map { it.id })

        f.editor.restoreDayVersion(f.snapshot(), date, at = 1000, now = 2000)
        val back = f.snapshot()
        assertEquals(listOf(temple, note, market), back.dayItemsFor(dayId).map { it.id })
        assertEquals(listOf(null, null, null), back.dayItemsFor(dayId).map { it.time })
        assertEquals(PlaceStatus.PLANNED, back.savedPlaces.first { it.id == "s2" }.status)
        assertEquals(PlaceStatus.IDEA, back.savedPlaces.first { it.id == "s3" }.status)
        // Restoring is a version too: the plan can come back.
        val h = back.queries()!!.dayFor(date)!!.history
        assertEquals(listOf("Vor Chat-Plan", "Vor dem Wiederherstellen"), h.map { it.label })
        f.editor.restoreDayVersion(back, date, at = 2000, now = 3000)
        assertEquals(listOf("Garten", "Tempel", "Onsen am Fluss", "Notiz"), f.snapshot().let { s -> s.dayItemsFor(dayId).map { i -> if (i.kind == DayItemKind.NOTE) "Notiz" else s.places[i.placeId]?.name } })
    }

    @Test fun historyKeepsTheLastVersionsOnly() {
        val f = fx()
        f.editor.addSuggestionToDay(f.snapshot(), date, "s1")
        repeat(MAX_DAY_VERSIONS + 3) { i -> f.editor.applyDayPlan(f.snapshot(), date, emptyList(), emptySet(), "v$i", now = i.toLong() + 1) }
        val h = f.snapshot().queries()!!.dayFor(date)!!.history
        assertEquals(MAX_DAY_VERSIONS, h.size)
        assertEquals("v${MAX_DAY_VERSIONS + 2}", h.last().label)
        assertFailsWith<IllegalArgumentException> { f.editor.restoreDayVersion(f.snapshot(), date, at = 1, now = 99) }
    }
}
