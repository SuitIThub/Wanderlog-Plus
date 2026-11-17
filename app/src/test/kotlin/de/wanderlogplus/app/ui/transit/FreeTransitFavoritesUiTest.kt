package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.FavoriteEnd
import de.wanderlogplus.shared.model.TransitFavorite
import de.wanderlogplus.shared.sync.TransitPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

/** ☆ for goals and routes, the history and "Schnell hin" in the free search and on Heute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class FreeTransitFavoritesUiTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val tempel = FavoriteEnd("Alter Bergtempel", 35.02, 135.78)
    private val hotel = FavoriteEnd("Gasthaus am Hang", 35.01, 135.76)
    private val added = mutableListOf<TransitFavorite>()
    private val removed = mutableListOf<String>()
    private var located = 0

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun show(snapshot: TripSnapshot, vm: TransitViewModel, history: TransitHistory, favId: String? = null) = rule.setContent {
        WanderlogPlusTheme {
            FreeTransitSearch(
                snapshot, vm, history, favId, finder = null, locate = { located++ },
                onAddFavorite = { added += it }, onRemoveFavorite = { removed += it },
                onSaveLogistics = {}, onSaveDay = { _, _, _ -> }, onBack = {},
            )
        }
    }

    private fun vm(from: TransitPoint? = null, to: TransitPoint? = null) =
        TransitViewModel({ null }, from, to, "2027-04-02", "10:00", null, retryDelaysMillis = emptyList())

    private fun history() = TransitHistory(File(tmp.root, "h.json"))

    @Test fun goalAndRouteAreStarredSeparately() {
        show(Fixtures.trip(), vm(TransitPoint(35.01, 135.76, "Gasthaus am Hang"), TransitPoint(35.02, 135.78, "Alter Bergtempel")), history())
        tap("transit-to-star")
        assertEquals(TransitFavorite("", to = tempel), added.single())
        tap("quick-route-star")
        assertEquals(TransitFavorite("", TransitFavorite.KIND_ROUTE, to = tempel, from = hotel), added.last())
        assertEquals(2, added.size)
    }

    @Test fun aStarredGoalIsRemovedAgain() {
        val snap = Fixtures.trip().copy(transitFavorites = listOf(TransitFavorite("f1", to = tempel)))
        show(snap, vm(to = TransitPoint(35.02, 135.78, "Alter Bergtempel")), history())
        tap("transit-to-star")
        assertEquals(listOf("f1"), removed)
        assertEquals(0, added.size)
    }

    @Test fun historyAndFavouritesFillTheForm() {
        val h = history().also { it.add(HistoryRoute(hotel, tempel)) }
        val snap = Fixtures.trip().copy(transitFavorites = listOf(TransitFavorite("f1", to = FavoriteEnd("Fischmarkt", 34.68, 135.51))))
        val v = vm()
        show(snap, v, h)
        tap("history-0")
        assertEquals("Gasthaus am Hang", v.state.value.form.fromPoint?.name)
        assertEquals("Alter Bergtempel", v.state.value.form.toPoint?.name)
        tap("fav-f1")
        assertEquals("Fischmarkt", v.state.value.form.toPoint?.name)
        assertEquals("Gasthaus am Hang", v.state.value.form.fromPoint?.name) // a goal only fills "Nach"
    }

    @Test fun schnellHinWithARouteSearchesAtOnce() {
        val h = history()
        val snap = Fixtures.trip().copy(transitFavorites = listOf(TransitFavorite("r1", TransitFavorite.KIND_ROUTE, to = tempel, from = hotel)))
        show(snap, vm(), h, favId = "r1")
        rule.waitForIdle()
        // The search ran: it is in the history (no server here, so no connections).
        assertEquals(listOf("Gasthaus am Hang" to "Alter Bergtempel"), h.read().map { it.from.name to it.to.name })
        assertEquals(0, located)
    }

    @Test fun schnellHinWithAGoalStartsFromHere() {
        val h = history()
        val snap = Fixtures.trip().copy(transitFavorites = listOf(TransitFavorite("g1", to = tempel)))
        val v = vm()
        show(snap, v, h, favId = "g1")
        rule.waitForIdle()
        assertEquals(1, located)
        assertEquals("Alter Bergtempel", v.state.value.form.toPoint?.name)
        assertNull(h.read().firstOrNull()) // not yet: waits for the position
        v.pickFrom(EndpointHit("Mein Standort", "", 35.0, 135.75, fromTrip = false))
        rule.waitForIdle()
        assertEquals("Mein Standort", h.read().single().from.name)
    }

    @Test fun heuteShowsTheFavouritesOfTheDaysRegion() {
        val favs = listOf(TransitFavorite("g1", to = tempel), TransitFavorite("g2", to = FavoriteEnd("Fischmarkt", 34.68, 135.51)))
        var opened: TransitFavorite? = null
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(
                    Fixtures.trip().copy(transitFavorites = favs), LocalDateTime.parse("2027-04-02T10:00"),
                    onOpenDay = {}, onOpenTrip = {}, onSyncNow = {}, onQuickRoute = { opened = it },
                )
            }
        }
        rule.onNodeWithTag("quick-route-g2").assertDoesNotExist() // the fish market is in another region
        tap("quick-route-g1")
        assertEquals("g1", opened?.id)
    }
}
