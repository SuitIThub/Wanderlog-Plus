package de.wanderlogplus.app.ui.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ShareTargetScreenTest {
    @get:Rule val rule = createComposeRule()

    private val targets = listOf(
        ShareTarget(EntityType.FLIGHT, "f1", "Flug ANA NH 203", "München → Tokio", ShareGroup.FLIGHT),
        ShareTarget(EntityType.SAVED_PLACE, "sp1", "Zoo", null, ShareGroup.PLACE),
    )

    @Test fun searchFilters() {
        var picked: ShareTarget? = null
        rule.setContent { WanderlogPlusTheme { ShareTargetScreen(targets, 1, onPick = { picked = it }) } }
        rule.onNodeWithText("Zoo").assertIsDisplayed()
        rule.onNodeWithText("Flug ANA NH 203").assertIsDisplayed()
        rule.onNodeWithTag("share-search").performTextInput("zoo")
        rule.onNodeWithText("Flug ANA NH 203").assertDoesNotExist()
        rule.onNodeWithText("Zoo").performClick()
        assertEquals("sp1", picked?.id)
    }

    @Test fun noMatchShowsMessage() {
        rule.setContent { WanderlogPlusTheme { ShareTargetScreen(targets, 1, onPick = {}) } }
        rule.onNodeWithTag("share-search").performTextInput("xyz")
        rule.onNodeWithText("Keine Treffer").assertIsDisplayed()
    }
}
