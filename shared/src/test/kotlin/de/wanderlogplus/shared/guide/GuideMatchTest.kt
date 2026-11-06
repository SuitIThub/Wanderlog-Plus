package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType.DAY
import de.wanderlogplus.shared.model.GuideTargetType.PLACE
import de.wanderlogplus.shared.model.GuideTargetType.STAY
import de.wanderlogplus.shared.model.GuideTargetType.TRAIN
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideMatchTest {
    private val world = GuideWorld(
        places = listOf(
            Place("p-ryo", name = "Tsutaya Ryokan", categories = listOf("Hotel")),
            Place("p-inari", name = "Fushimi Inari", categories = listOf("Shinto shrine", "Sights & Landmarks")),
            Place("p-sushi", name = "Sushi Saito", categories = listOf("Japanese restaurant")),
            Place("p-bar", name = "Omakase Bar"),
            Place("p-empty"),
        ).associateBy { it.id },
        saved = listOf(
            SavedPlace("s-inari", "p-inari", category = PlaceCategory.SIGHT),
            SavedPlace("s-sushi", "p-sushi", category = PlaceCategory.FOOD),
            SavedPlace("s-bar", "p-bar", category = PlaceCategory.SIGHT),
            SavedPlace("s-empty", "p-empty", category = PlaceCategory.SIGHT),
        ),
        stays = listOf(Stay("st", "p-ryo", checkIn = "2027-05-19", checkOut = "2027-05-21")),
        trains = listOf(
            Train("tr1", number = "Nozomi 21", depart = Endpoint("Tokyo", date = "2027-05-19", time = "08:00"), arrive = Endpoint("Nagoya", date = "2027-05-19")),
            Train("tr2", carrier = "Deutsche Bahn", depart = Endpoint("Frankfurt", date = "2027-05-17")),
        ),
        flights = emptyList(),
        guides = emptyList(),
        days = listOf(Day("d19", "2027-05-19")),
        dayItems = listOf(DayItem("i1", "d19", "a", placeId = "p-inari")),
    )

    private fun t(type: de.wanderlogplus.shared.model.GuideTargetType, id: String) = GuideTarget(type, id)

    @Test
    fun `ryokan stay touches onsen, ryokan and hotel`() {
        assertEquals(listOf("onsen", "ryokan", "hotel"), GuideMatch.topicsFor(t(STAY, "st"), world))
    }

    @Test
    fun `shinkansen train touches bahn and shinkansen`() {
        assertEquals(listOf("bahn", "shinkansen"), GuideMatch.topicsFor(t(TRAIN, "tr1"), world))
        assertEquals(listOf("bahn"), GuideMatch.topicsFor(t(TRAIN, "tr2"), world))
    }

    @Test
    fun `sushi place touches restaurant and omakase, a shrine its own topic`() {
        assertEquals(listOf("restaurant", "omakase"), GuideMatch.topicsFor(t(PLACE, "s-sushi"), world))
        assertEquals(listOf("tempel-schrein"), GuideMatch.topicsFor(t(PLACE, "s-inari"), world))
    }

    @Test
    fun `a place without name or categories touches nothing`() {
        assertEquals(emptyList(), GuideMatch.topicsFor(t(PLACE, "s-empty"), world))
        assertEquals(emptyList(), GuideMatch.topicsFor(t(PLACE, "s-unknown"), world))
    }

    @Test
    fun `always topics only on days, and a day has what happens on it`() {
        val day = GuideMatch.topicsFor(t(DAY, "2027-05-19"), world)
        assertTrue("sprache-basics" in day && "bezahlen" in day, day.toString())
        assertTrue(listOf("onsen", "ryokan", "hotel", "tempel-schrein", "bahn", "shinkansen").all { it in day }, day.toString())
        assertFalse("restaurant" in day)
        assertFalse("sprache-basics" in GuideMatch.topicsFor(t(PLACE, "s-inari"), world))
    }

    @Test
    fun `guides for a target follow rules, pins and hides`() {
        val restaurant = Guide("g-r", topic = "restaurant", title = "Im Restaurant bestellen")
        val omakase = Guide("g-o", topic = "omakase", parentId = "g-r", title = "Omakase")
        val free = Guide("g-f", title = "Fuchs-Schreine", pinnedTo = listOf(t(PLACE, "s-inari")))
        val ghost = Guide("g-x", title = "Alt", pinnedTo = listOf(t(PLACE, "s-gone")))
        val w = world.copy(guides = listOf(restaurant, omakase, free, ghost))
        assertEquals(setOf("g-r", "g-o"), GuideMatch.guidesFor(t(PLACE, "s-sushi"), w).map { it.id }.toSet())
        // A matching sub-topic brings its parent along, even when the parent's own rule does not match.
        assertEquals(setOf("g-r", "g-o"), GuideMatch.guidesFor(t(PLACE, "s-bar"), w).map { it.id }.toSet())
        // Hidden here: gone, the parent stays through its own rule.
        val hidden = w.copy(guides = listOf(restaurant, omakase.copy(hiddenFrom = listOf(t(PLACE, "s-sushi"))), free, ghost))
        assertEquals(listOf("g-r"), GuideMatch.guidesFor(t(PLACE, "s-sushi"), hidden).map { it.id })
        assertEquals(listOf("g-f"), GuideMatch.guidesFor(t(PLACE, "s-inari"), w).map { it.id })
        assertFalse("g-f" in GuideMatch.guidesFor(t(PLACE, "s-sushi"), w).map { it.id })
        // A pin to a deleted place shows nowhere.
        assertEquals(emptyList(), GuideMatch.guidesFor(t(PLACE, "s-gone"), w))
    }

    @Test
    fun `duplicates by topic or title`() {
        val onsen = Guide("g1", topic = "onsen", title = "Onsen & Sentō")
        val knigge = Guide("g2", title = "Onsen Knigge")
        val ramen = Guide("g3", title = "Ramen")
        assertEquals(onsen, GuideMatch.duplicateOf("onsen", "x", listOf(onsen, knigge)))
        assertEquals(knigge, GuideMatch.duplicateOf(null, "Onsen-Knigge", listOf(ramen, knigge)))
        assertNull(GuideMatch.duplicateOf(null, "Omakase", listOf(ramen)))
        assertNull(GuideMatch.duplicateOf("ryokan", "Im Ryokan", listOf(ramen)))
    }
}
