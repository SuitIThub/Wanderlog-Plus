package de.wanderlogplus.app.ui.today

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.guide.DayGuide
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodayGuidesTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun chipsPreviewAndOpen() {
        val shin = Guide("g-shin", topic = "shinkansen", title = "Shinkansen", summary = "Reservierte Plätze, Gepäck anmelden.",
            sections = listOf(GuideSection("Im Zug", dos = listOf("Leise telefonieren"), donts = listOf("Sitz drehen ohne Fragen"))))
        val ryo = Guide("g-ryo", topic = "ryokan", title = "Im Ryokan")
        var opened: String? = null
        var suggestions = 0
        rule.setContent {
            WanderlogPlusTheme {
                TodayGuidesWidget(
                    listOf(DayGuide(shin, 11 * 60 + 33, false), DayGuide(ryo, 15 * 60, false)), suggested = 2, accent = Color.Blue,
                    onOpenGuide = { opened = it }, onSuggestions = { suggestions++ },
                )
            }
        }
        rule.onAllNodesWithTag("today-guide").assertCountEquals(2)
        rule.onNodeWithText("11:33 · Shinkansen").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Reservierte Plätze, Gepäck anmelden.").assertExists()
        rule.onNodeWithText("✓ Leise telefonieren").assertExists()
        // Robolectric does not lay the sheet out on screen, so a touch misses it: trigger the button's click action.
        rule.onNodeWithTag("today-guide-open").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("g-shin", opened)
        rule.onNodeWithText("2 Guides vorgeschlagen").performClick()
        assertEquals(1, suggestions)
    }
}
