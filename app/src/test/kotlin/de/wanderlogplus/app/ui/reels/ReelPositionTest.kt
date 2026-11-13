package de.wanderlogplus.app.ui.reels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.ReelCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ReelPositionTest {
    @get:Rule val rule = createComposeRule()

    private val missing = ReelCandidate(name = "Sunshine 60 Observatory", city = "Tokio", positionIssue = "unavailable")
    private val found = GeoPlace("Sunshine 60", "Ikebukuro, Tokyo", 35.7295, 139.7178, "tourism", "attraction")

    @Test fun missingPositionExplainsWhyAndOffersSearch() {
        assertEquals("Ohne Kartenposition: Der Ortsdienst hat nicht geantwortet.", missingPositionText(missing))
        assertEquals("Ohne Kartenposition: Der Ort wurde auf der Karte nicht gefunden.", missingPositionText(missing.copy(positionIssue = "not_found")))
        var asked: Int? = null
        val job = AiJobInfo("j", "reel", 0, "DONE", candidates = listOf(missing))
        rule.setContent {
            WanderlogPlusTheme { ReelJobScreen(job, Fixtures.trip(), imported = false, onImport = { _, _, _ -> }, onOpenPlaces = {}, onBack = {}, onFindPosition = { asked = it }) }
        }
        rule.onNodeWithTag("reel-no-position-0").assertExists()
        rule.onNodeWithTag("reel-find-position-0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(0, asked)
    }

    @Test fun aPickedPositionMakesTheCandidateImportableAndChecked() {
        val placed = withPosition(missing, found)
        assertTrue(importable(placed))
        assertEquals("Ikebukuro, Tokyo", placed.address)
        assertEquals("Position selbst gesetzt", routeLabel(placed))

        var job by mutableStateOf(AiJobInfo("j", "reel", 0, "DONE", candidates = listOf(missing)))
        var picks: List<ReelPick>? = null
        rule.setContent {
            WanderlogPlusTheme { ReelJobScreen(job, Fixtures.trip(), imported = false, onImport = { p, _, _ -> picks = p }, onOpenPlaces = {}, onBack = {}, onFindPosition = {}) }
        }
        job = job.copy(candidates = listOf(placed)) // back from the search
        rule.waitForIdle()
        rule.onNodeWithTag("reel-no-position-0").assertDoesNotExist()
        rule.onNodeWithText("Position selbst gesetzt", substring = true).assertExists()
        rule.onNodeWithTag("reel-pick-0").assertIsOn()
        rule.onNodeWithTag("reel-import").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(35.7295, picks!!.single().candidate.lat!!, 0.0)
    }
}
