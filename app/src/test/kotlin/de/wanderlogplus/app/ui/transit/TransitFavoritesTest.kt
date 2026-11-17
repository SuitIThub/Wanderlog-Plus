package de.wanderlogplus.app.ui.transit

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.model.FavoriteEnd
import de.wanderlogplus.shared.model.TransitFavorite
import de.wanderlogplus.shared.sync.TransitPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransitFavoritesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val tempel = FavoriteEnd("Alter Bergtempel", 35.02, 135.78)
    private val markt = FavoriteEnd("Fischmarkt", 34.68, 135.51)
    private val kyoto = FavoriteEnd("京都", station = "京都")

    @Test fun endsMatchByStationOrClosePosition() {
        assertTrue(tempel.sameAs(FavoriteEnd("Tempel", 35.0205, 135.7805))) // ~70 m
        assertFalse(tempel.sameAs(markt))
        assertTrue(kyoto.sameAs(FavoriteEnd("Kyoto", station = "京都")))
        assertFalse(kyoto.sameAs(FavoriteEnd("", station = "")))
    }

    @Test fun formEndsFromAPickedPointOrAStation() {
        assertEquals(tempel, formEnd(TransitPoint(35.02, 135.78, "Alter Bergtempel"), ""))
        assertEquals(FavoriteEnd("京都", station = "京都"), formEnd(null, " 京都 "))
        // A picked point with a station chosen below it: the station is kept too.
        assertEquals(FavoriteEnd("Hotel", 35.0, 135.7, station = "四条"), formEnd(TransitPoint(35.0, 135.7, "Hotel"), "四条"))
        assertNull(formEnd(null, " "))
    }

    @Test fun favouritesOfTheDaysRegionIncludingDayTrips() {
        val favs = listOf(
            TransitFavorite("a", to = tempel),
            TransitFavorite("b", to = markt),
            TransitFavorite("c", kind = TransitFavorite.KIND_ROUTE, from = markt, to = tempel),
            TransitFavorite("d", to = FavoriteEnd("Ufer", 35.30, 136.00)), // day trip region "Seenland" from Bergstadt
            TransitFavorite("e", to = kyoto), // a station without position: no region
        )
        val snap = Fixtures.trip().copy(transitFavorites = favs)
        assertEquals(listOf("a", "c", "d"), favoritesForRegion(snap, "r-berg").map { it.id })
        assertEquals(listOf("b", "c"), favoritesForRegion(snap, "r-kueste").map { it.id })
        assertTrue(favoritesForRegion(snap, null).isEmpty())
    }

    @Test fun historyKeepsTheLastTenRoutesNewestFirstWithoutDoubles() {
        val h = TransitHistory(File(tmp.root, "h.json"))
        assertTrue(h.read().isEmpty())
        h.add(HistoryRoute(markt, tempel))
        h.add(HistoryRoute(kyoto, markt))
        h.add(HistoryRoute(markt, FavoriteEnd("Alter Bergtempel", 35.0201, 135.7801))) // the same route again
        assertEquals(listOf("Alter Bergtempel", "Fischmarkt"), h.read().map { it.to.name })
        repeat(12) { i -> h.add(HistoryRoute(FavoriteEnd("A$i", station = "A$i"), FavoriteEnd("B$i", station = "B$i"))) }
        assertEquals(10, h.read().size)
        assertEquals("B11", h.read().first().to.name)
        // A broken file is an empty history, not a crash.
        File(tmp.root, "h.json").writeText("{kaputt")
        assertTrue(h.read().isEmpty())
    }
}
