package de.wanderlogplus.app.ui.places

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.Color
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.TabelogInfo
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.sync.Worth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RatingStripTest {
    @get:Rule val rule = createComposeRule()

    private val tabelog = TabelogInfo(TabelogStatus.FOUND, "https://tabelog.com/kyoto/A2601/A260201/26032368/", score = 3.71)
    private val place = Place("p", name = "Menya Inoichi", rating = 4.6, numRatings = 1200, tabelog = tabelog)
    private val saved = SavedPlace("s", "p", category = PlaceCategory.FOOD, worth = Worth.MUST)

    @Test fun allThree() {
        assertEquals(
            listOf(RatingKind.GOOGLE to "4,6 (1.200)", RatingKind.TABELOG to "3,71", RatingKind.AI to "Muss"),
            ratingItems(place, saved).map { it.kind to it.text },
        )
    }

    @Test fun onlyWhatThereIs() {
        assertEquals(listOf(RatingKind.GOOGLE to "4,6"), ratingItems(Place("p", rating = 4.6), SavedPlace("s", "p")).map { it.kind to it.text })
        assertEquals(listOf(RatingKind.TABELOG to "3,71"), ratingItems(Place("p", tabelog = tabelog), SavedPlace("s", "p")).map { it.kind to it.text })
        assertEquals(emptyList<RatingItem>(), ratingItems(Place("p", tabelog = tabelog.copy(status = TabelogStatus.NOT_FOUND)), SavedPlace("s", "p")))
        assertEquals(emptyList<RatingItem>(), ratingItems(null, SavedPlace("s", "p")))
        assertEquals(listOf("Lohnt", "Wenn Zeit", "Auslassen"), listOf(Worth.WORTH, Worth.OPTIONAL, Worth.SKIP).map { ratingItems(null, SavedPlace("s", "p", worth = it)).single().text })
    }

    @Test fun stripInACardWithDescriptions() {
        rule.setContent {
            WanderlogPlusTheme {
                PhotoCard(title = "Menya Inoichi", subtitle = null, imageUrl = null, placeholderSymbol = "🍜", accent = Color.Blue, extra = { RatingStrip(place, saved) })
            }
        }
        rule.onNodeWithTag("rating-strip", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("4,6 (1.200)", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3,71", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Muss", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("Tabelog-Bewertung", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("KI-Einschätzung", useUnmergedTree = true).assertExists()
    }

    @Test fun nothingWithoutValues() {
        rule.setContent { WanderlogPlusTheme { RatingStrip(Place("p"), SavedPlace("s", "p")) } }
        rule.onNodeWithTag("rating-strip", useUnmergedTree = true).assertDoesNotExist()
    }
}
