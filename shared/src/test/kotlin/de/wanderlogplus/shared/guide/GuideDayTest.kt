package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.query.DayFlow
import de.wanderlogplus.shared.query.Movement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Tokyo → Kiso moving day of DayFlowTest, with guides. */
class GuideDayTest {
    private val regions = listOf(Region("tokyo", "Tokio"), Region("kiso", "Kiso"))
    private val places = listOf(
        Place("p-hotel", name = "Hotel Tokio", lat = 35.68, lng = 139.76),
        Place("p-ryokan", name = "Ryokan Kiso", lat = 35.52, lng = 137.57),
        Place("p-museum", name = "Museum", lat = 35.69, lng = 139.70, categories = listOf("Museum")),
    ).associateBy { it.id }
    private val saved = listOf(SavedPlace("s1", "p-museum", "tokyo"))
    private val tokyo = Stay("st1", "p-hotel", "tokyo", "2027-05-10", "2027-05-14", checkOutTime = "10:00")
    private val kiso = Stay("st2", "p-ryokan", "kiso", "2027-05-14", "2027-05-16", checkInTime = "15:00")
    private val train = Train(
        "t1", "JR", Endpoint("Tokyo", lat = 35.681, lng = 139.767, date = "2027-05-14", time = "11:33"),
        Endpoint("Nakatsugawa", lat = 35.49, lng = 137.50, date = "2027-05-14", time = "14:40"), number = "Hikari 501",
    )
    private val date = "2027-05-14"
    private val guides = listOf(
        Guide("g-shin", topic = "shinkansen", title = "Shinkansen"),
        Guide("g-ryo", topic = "ryokan", title = "Im Ryokan"),
        Guide("g-onsen", topic = "onsen", title = "Onsen & Sentō"),
        Guide("g-lang", topic = "sprache-basics", title = "Japanisch für unterwegs"),
    )
    private val world = GuideWorld(places, saved, listOf(tokyo, kiso), listOf(train), emptyList(), guides, listOf(Day("d", date)), emptyList())
    private val flow = DayFlow.build(date, emptyList(), listOf(Movement.TrainMove(train)), listOf(tokyo, kiso), saved, places, regions)

    @Test
    fun `day guides come in the order of the day`() {
        val morning = GuideMatch.forDay(date, flow, world, nowMinutes = 8 * 60)
        assertEquals(listOf("g-shin", "g-onsen", "g-ryo", "g-lang"), morning.map { it.guide.id })
        assertEquals(11 * 60 + 33, morning.first().atMinutes)
        assertTrue(morning.none { it.done })
        val evening = GuideMatch.forDay(date, flow, world, nowMinutes = 20 * 60)
        assertEquals(listOf("g-onsen", "g-ryo", "g-lang", "g-shin"), evening.map { it.guide.id })
        assertTrue(evening.last().done)
        assertFalse(evening.first().done, "tonight's stay is never done")
    }

    @Test
    fun `suggestions are trip topics without guides and dismissed ones`() {
        val bare = world.copy(guides = emptyList())
        val days = listOf(date)
        val all = GuideSuggestions.topicsInTrip(bare, days)
        assertTrue(listOf("sprache-basics", "onsen", "ryokan", "hotel", "bahn", "shinkansen", "museum").all { it in all }, all.toString())
        assertEquals(all, all.distinct())
        assertTrue("ryokan" in GuideSuggestions.open(bare, days, GuidePrefs()))
        assertFalse("ryokan" in GuideSuggestions.open(bare, days, GuidePrefs(dismissedTopics = listOf("ryokan"))))
        assertFalse("ryokan" in GuideSuggestions.open(world, days, GuidePrefs()))
        // Two devices made the same guide offline: the topic counts once as covered.
        val twice = world.copy(guides = guides + Guide("g-ryo2", topic = "ryokan", title = "Ryokan"))
        assertEquals(GuideSuggestions.open(world, days, GuidePrefs()), GuideSuggestions.open(twice, days, GuidePrefs()))
        assertEquals(listOf("bezahlen", "hotel", "bahn"), GuideSuggestions.openOn(date, world, GuidePrefs()))
    }
}
