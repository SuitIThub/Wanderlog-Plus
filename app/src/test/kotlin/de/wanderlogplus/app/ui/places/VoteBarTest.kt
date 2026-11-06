package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Vote
import de.wanderlogplus.shared.model.Votes
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class VoteBarTest {
    @get:Rule val rule = createComposeRule()

    private fun vote(place: String, voter: String, value: Int) = Vote(Votes.id(place, voter), place, voter, value)

    @Test
    fun voteEditsMoveToShortlistAndBack() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev", now = { 1000L }))
        editor.create(EntityType.SAVED_PLACE, EntityCodec.fields(SavedPlace("s1", "p1")), id = "s1")
        val first = vote("s1", "selina", Votes.LIKE)
        editor.create(EntityType.VOTE, EntityCodec.fields(first), id = first.id)
        val snap = { TripSnapshot.from(store.all()) }
        voteEdits(snap(), "s1", "daniel", Votes.LIKE)(editor)
        assertEquals(setOf("selina", "daniel"), snap().votes.map { it.voter }.toSet())
        assertEquals(PlaceStatus.SHORTLIST, snap().savedPlaces.single().status)
        voteEdits(snap(), "s1", "daniel", 0)(editor)
        assertEquals(0, snap().votes.first { it.voter == "daniel" }.value)
        assertEquals(PlaceStatus.IDEA, snap().savedPlaces.single().status)
    }

    @Test
    fun voteBarShowsNamesAndToggles() {
        val votes = listOf(vote("s1", "selina", Votes.LIKE), vote("s1", "daniel", Votes.DOUBLE))
        val cast = mutableListOf<Int>()
        rule.setContent { WanderlogPlusTheme { VoteBar(votes, me = "daniel", onVote = { cast += it }) } }
        rule.onNodeWithText("daniel 👍👍 · selina 👍").assertExists()
        rule.onNodeWithText("+3").assertExists()
        rule.onNodeWithTag("vote-double").performSemanticsAction(SemanticsActions.OnClick) // mine already: withdraw
        rule.onNodeWithTag("vote-dislike").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(0, Votes.DISLIKE), cast)
    }

    private fun doubted(): TripSnapshot {
        val s = Fixtures.trip()
        return s.copy(votes = listOf(vote("s-tempel", "selina", Votes.DISLIKE)))
    }

    @Test
    fun todayShowsDoubtForPlannedPlace() {
        rule.setContent {
            WanderlogPlusTheme {
                de.wanderlogplus.app.ui.today.TodayScreen(doubted(), LocalDateTime.parse("2027-03-01T10:00"), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {})
            }
        }
        rule.onNodeWithTag("plan-doubt-s-tempel").assertExists()
        rule.onNodeWithText("selina 👎", substring = true).assertExists()
    }

    @Test
    fun dayShowsDoubt() {
        rule.setContent {
            WanderlogPlusTheme {
                de.wanderlogplus.app.ui.trip.DayDetailScreen(doubted(), "2027-04-02", onBack = null, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {})
            }
        }
        rule.onNodeWithTag("plan-doubt-s-tempel").assertExists()
    }

    @Test
    fun placePageVotesAndListShowsTheScore() {
        val s = Fixtures.trip().copy(votes = listOf(vote("s-nudel", "selina", Votes.DOUBLE)))
        var page by androidx.compose.runtime.mutableStateOf("detail")
        val cast = mutableListOf<Pair<String, Int>>()
        rule.setContent {
            WanderlogPlusTheme {
                if (page == "detail") PlaceDetailScreen(s, "s-nudel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, me = "daniel", onVote = { id, v -> cast += id to v })
                else RegionPlacesScreen(s, "r-berg", onBack = {}, onOpenPlace = {})
            }
        }
        rule.onNodeWithText("selina 👍👍").assertExists()
        rule.onNodeWithTag("vote-like").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("s-nudel" to Votes.LIKE), cast)
        page = "list"
        rule.onNodeWithTag("vote-score-s-nudel", useUnmergedTree = true).assertExists()
    }
}
