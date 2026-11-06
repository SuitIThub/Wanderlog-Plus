package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.Amenity
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.PropertyInfo
import de.wanderlogplus.shared.model.RoomInfo
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.StayInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StayInfoCardsTest {
    @get:Rule val rule = createComposeRule()

    private val info = StayInfo(
        room = RoomInfo(type = "Japanisches Zimmer", beds = "2 Futons", bath = "eigenes Rotenburo", amenities = listOf(AmenityGroup("Bad", listOf(Amenity("Rotenburo", "privat"))))),
        property = PropertyInfo(meals = "Kaiseki 18:00", amenities = listOf(AmenityGroup("Service", listOf(Amenity("Wäscherei"))))),
        sources = listOf("https://ryokan.example/rooms"),
    )

    @Test
    fun cardsShowRoomPropertyAndSources() {
        rule.setContent { WanderlogPlusTheme { StayInfoCards(Stay("st", info = info), emptyList(), null, onResearch = null, onEdit = null, running = null) } }
        rule.onNodeWithText("Japanisches Zimmer").assertExists()
        rule.onNodeWithText("2 Futons", substring = true).assertExists()
        rule.onNodeWithText("Bad: Rotenburo – privat").assertExists()
        rule.onNodeWithText("Service: Wäscherei").assertExists()
        rule.onNodeWithText("Kaiseki 18:00", substring = true).assertExists()
        rule.onNodeWithText("ryokan.example").assertExists()
    }

    @Test
    fun researchTakesLinksAndAsksBeforeReplacingEverything() {
        var asked: Pair<List<String>, Boolean>? = null
        rule.setContent {
            WanderlogPlusTheme {
                StayInfoCards(Stay("st", infoLinks = listOf("https://ryokan.example")), emptyList(), null, onResearch = { l, f -> asked = l to f }, onEdit = {}, running = null)
            }
        }
        rule.onNodeWithText("Zimmer & Ausstattung recherchieren").assertExists()
        rule.onNodeWithTag("stay-research").performClick()
        rule.onNodeWithTag("stay-research-links").performTextInput("https://booking.example/room\n")
        rule.onNodeWithTag("stay-research-start").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("https://booking.example/room", "https://ryokan.example") to false, asked!!.first.sorted() to asked!!.second)
        rule.onNodeWithTag("stay-research").performClick()
        rule.onNodeWithTag("stay-research-fresh").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("stay-research-fresh-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, asked!!.second)
    }
}
