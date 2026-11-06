package de.wanderlogplus.app.ui.places

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PlaceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class RegionResearchTest {
    @get:Rule val rule = createComposeRule()

    /** Fixture with s-tempel researched and s-garten dropped. */
    private fun trip(): TripSnapshot = Fixtures.trip().let { t ->
        t.copy(savedPlaces = t.savedPlaces.map {
            when (it.id) {
                "s-tempel" -> it.copy(dossier = PlaceDossier(verdict = "x", researchedAt = 5))
                "s-garten" -> it.copy(status = PlaceStatus.DROPPED)
                else -> it
            }
        })
    }

    @Test
    fun choicesCountFreshAndSkipDropped() {
        val s = trip()
        val berg = regionChoices(s).first { it.key == "r-berg" }
        assertEquals("Bergstadt", berg.name)
        assertTrue("s-tempel" in berg.all)
        assertFalse("s-garten" in berg.all)
        assertFalse("s-tempel" in berg.fresh)
        assertTrue("s-nudel" in berg.fresh)
        assertTrue(regionChoices(s).none { it.all.isEmpty() })
    }

    private fun sheet(onStart: (List<Pair<String, List<String>>>) -> Unit) {
        val choices = regionChoices(trip())
        rule.setContent { WanderlogPlusTheme { RegionResearchSheet(choices, onStart = onStart, onDismiss = {}) } }
    }

    @Test
    fun startsOneBatchPerRegionInOrder() {
        val choices = regionChoices(trip())
        var batches: List<Pair<String, List<String>>>? = null
        sheet { batches = it }
        choices.take(2).forEach { rule.onNodeWithTag("research-region-${it.key}").performSemanticsAction(SemanticsActions.OnClick) }
        rule.onNodeWithTag("research-new").performSemanticsAction(SemanticsActions.OnClick)
        val expected = choices.take(2).filter { it.fresh.isNotEmpty() }.map { it.name to it.fresh }
        assertEquals(expected, batches)
    }

    @Test
    fun allButtonTakesAll() {
        val choices = regionChoices(trip())
        var batches: List<Pair<String, List<String>>>? = null
        sheet { batches = it }
        rule.onNodeWithTag("research-region-r-berg").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("research-all").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Bergstadt" to choices.first { it.key == "r-berg" }.all), batches)
    }

    @Test
    fun buttonsDisabledWithoutSelection() {
        sheet { }
        rule.onNodeWithTag("research-all").assertIsNotEnabled()
        rule.onNodeWithTag("research-new").assertIsNotEnabled()
        rule.onNodeWithTag("research-region-r-berg").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("research-all").assertIsEnabled()
    }

    @Test
    fun selectAllSumsCounts() {
        val choices = regionChoices(trip())
        sheet { }
        rule.onNodeWithTag("research-select-all").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Alle (${choices.sumOf { it.all.size }} · ", substring = true).assertExists()
        rule.onNodeWithText("Nur unrecherchierte (${choices.sumOf { it.fresh.size }} · ", substring = true).assertExists()
    }

    @Test
    fun overviewOffersResearchNotAssessment() {
        rule.setContent { WanderlogPlusTheme { PlacesScreen(trip(), onOpenRegion = {}, onResearchRegions = {}) } }
        rule.onNodeWithTag("places-research").assertExists()
        rule.onNodeWithTag("places-assess").assertDoesNotExist()
        rule.onNodeWithTag("places-research").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Regionen recherchieren").assertExists()
    }
}
