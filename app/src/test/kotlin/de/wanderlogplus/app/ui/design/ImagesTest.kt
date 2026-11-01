package de.wanderlogplus.app.ui.design

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class ImagesTest {
    private val base = "https://itin-dev.wanderlogstatic.com/freeImageMedium/"

    @Test fun placeImageUrlUsesMediumBase() {
        assertEquals(base + "abc123", placeImageUrl("abc123"))
    }

    @Test fun heroImageSkipsBlankKeys() {
        assertEquals(base + "k2", Place("p", imageKeys = listOf("", "  ", "k2", "k3")).heroImageUrl())
        assertNull(Place("p", imageKeys = listOf("", " ")).heroImageUrl())
    }

    @Test fun dayHeroImageUsesFirstPlaceItem() {
        // Day 2 starts with a NOTE item; the first PLACE item (temple) wins over the later noodle shop.
        assertEquals(base + "img-tempel", dayHeroImage(Fixtures.trip(), "2027-04-02"))
        // Sort order, not list order, decides which item is first.
        val s = Fixtures.trip()
        val reordered = s.copy(dayItems = s.dayItems.map { if (it.id == "i-3") it.copy(sortKey = "a0") else it })
        assertEquals(base + "img-nudel", dayHeroImage(reordered, "2027-04-02"))
    }

    @Test fun regionHeroImageUsesBestRated() {
        // Berg: temple 4.8 beats noodle shop 4.6; the 4.5 garden has no image anyway.
        assertEquals(base + "img-tempel", regionHeroImage(Fixtures.trip(), "r-berg"))
        assertEquals(base + "img-markt", regionHeroImage(Fixtures.trip(), "r-kueste"))
    }

    @Test fun nullWithoutImages() {
        val s = Fixtures.trip()
        val bare = s.copy(places = s.places.mapValues { (_, p) -> p.copy(imageKeys = emptyList()) })
        assertNull(dayHeroImage(bare, "2027-04-02"))
        assertNull(regionHeroImage(bare, "r-berg"))
        // Days without items, unknown dates and unknown regions give null too.
        assertNull(dayHeroImage(s, "2027-04-04"))
        assertNull(dayHeroImage(s, "1999-01-01"))
        assertNull(regionHeroImage(s, "nope"))
        val noteOnly = s.copy(dayItems = listOf(DayItem("x", "d-4", "a0", DayItemKind.NOTE, text = "nur Notiz")))
        assertNull(dayHeroImage(noteOnly, "2027-04-04"))
    }

    /** Only with the private real export; asserts shape, never prints values. */
    @Test fun realDataGivesRegionImages() {
        val real = Fixtures.realOrNull()
        assumeTrue("real export not present", real != null)
        assertNotNull(real!!.regions.firstNotNullOfOrNull { regionHeroImage(real, it.id) })
    }
}
