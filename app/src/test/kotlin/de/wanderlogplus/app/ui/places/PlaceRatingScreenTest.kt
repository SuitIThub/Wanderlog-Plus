package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Vote
import de.wanderlogplus.shared.model.Votes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlaceRatingScreenTest {
    @get:Rule val rule = createComposeRule()

    private val cast = mutableListOf<Pair<String, Int>>()
    private var done = false

    private fun show(snap: () -> TripSnapshot) {
        rule.setContent {
            WanderlogPlusTheme {
                PlaceRatingScreen(snap(), me = "daniel", onVote = { id, v -> cast += id to v }, onOpenPlace = {}, onDone = { done = true })
            }
        }
    }

    private var mapFor: String? = null

    private fun showWithMap(snap: TripSnapshot) {
        rule.setContent {
            WanderlogPlusTheme {
                PlaceRatingScreen(snap, me = "daniel", onVote = { _, _ -> }, onOpenPlace = {}, onDone = {}, onOpenMap = { mapFor = it })
            }
        }
    }

    @Test
    fun mapUnderTheTextOpensTheFullMap() {
        showWithMap(Fixtures.trip())
        rule.onNodeWithTag("place-map-card", useUnmergedTree = true).assertExists()
        tap("place-map-fullscreen")
        assertEquals("s-tempel", mapFor)
    }

    @Test
    fun noMapWithoutCoordinates() {
        val base = Fixtures.trip()
        showWithMap(base.copy(places = base.places + ("p-tempel" to base.places.getValue("p-tempel").copy(lat = 0.0, lng = 0.0))))
        rule.onNodeWithTag("rating-card-s-tempel").assertExists()
        rule.onNodeWithTag("place-map-card", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun votesAndAdvances() {
        show { Fixtures.trip() }
        rule.onNodeWithTag("rating-card-s-tempel").assertExists()
        rule.onNodeWithTag("rate-progress").assertTextEquals("1 / 5")
        tap("rate-like")
        assertEquals(listOf("s-tempel" to Votes.LIKE), cast)
        rule.onNodeWithTag("rating-card-s-garten").assertExists()
        rule.onNodeWithTag("rate-progress").assertTextEquals("2 / 5")
        tap("rate-neutral")
        assertEquals("s-garten" to 0, cast.last())
    }

    @Test
    fun skipDoesNotVote() {
        show { Fixtures.trip() }
        tap("rate-skip")
        assertTrue(cast.isEmpty())
        rule.onNodeWithTag("rating-card-s-garten").assertExists()
    }

    @Test
    fun showsStayDistanceForDayTrip() {
        show { Fixtures.trip() }
        rule.onNodeWithTag("rating-stay", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rating-centre", useUnmergedTree = true).assertDoesNotExist()
        repeat(3) { tap("rate-skip") }
        rule.onNodeWithTag("rating-card-s-ufer").assertExists()
        rule.onNodeWithText("zur Unterkunft (Bergstadt)", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("🏨 ", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("rating-centre", useUnmergedTree = true).assertExists()
    }

    @Test
    fun skipsPlacesGoneOrRatedMeanwhile() {
        val t = Fixtures.trip()
        var snap by mutableStateOf(t)
        show { snap }
        snap = t.copy(
            savedPlaces = t.savedPlaces.filter { it.id != "s-garten" },
            votes = listOf(Vote(Votes.id("s-nudel", "daniel"), "s-nudel", "daniel", Votes.LIKE)),
        )
        tap("rate-like")
        rule.onNodeWithTag("rating-card-s-ufer").assertExists()
    }

    @Test
    fun endsWithDone() {
        show { Fixtures.trip() }
        repeat(5) { tap("rate-skip") }
        rule.onNodeWithTag("rate-done").assertExists()
        rule.onNodeWithText("Alles bewertet 🎉").assertExists()
        rule.onNodeWithText("Zurück zu den Orten").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(done)
    }
}
