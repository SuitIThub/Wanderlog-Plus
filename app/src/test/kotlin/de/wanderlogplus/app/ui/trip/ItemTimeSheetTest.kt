package de.wanderlogplus.app.ui.trip

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemTimeSheetTest {
    @get:Rule val rule = createComposeRule()
    private var saved: Pair<String?, Int?>? = null

    private fun show(fixedTime: String? = null, stayMinutes: Int? = null) = rule.setContent {
        WanderlogPlusTheme {
            ItemTimeContent(estimateStart = 13 * 60 + 5, defaultMinutes = 60, fixedTime = fixedTime, stayMinutes = stayMinutes, onSave = { f, m -> saved = f to m })
        }
    }

    @Test fun switchOnPrefillsTheEstimate() {
        show()
        rule.onNodeWithTag("time-fixed-value").assertDoesNotExist()
        rule.onNodeWithTag("time-fixed-switch").performClick()
        rule.onNodeWithTag("time-fixed-value").assertTextContains("13:05")
        rule.onNodeWithTag("time-save").performClick()
        assertEquals("13:05" to null, saved)
    }

    @Test fun aFixedTimeCanBeSwitchedOff() {
        show(fixedTime = "18:00", stayMinutes = 90)
        rule.onNodeWithTag("time-fixed-value").assertTextContains("18:00")
        rule.onNodeWithTag("time-fixed-switch").performClick()
        rule.onNodeWithTag("time-save").performClick()
        assertEquals(null to 90, saved)
    }

    @Test fun durationStepsAndReset() {
        show()
        rule.onNodeWithTag("time-duration").assertTextContains("60 Min")
        rule.onNodeWithTag("time-duration-plus").performClick()
        rule.onNodeWithTag("time-duration-plus").performClick()
        rule.onNodeWithTag("time-duration").assertTextContains("90 Min")
        rule.onNodeWithTag("time-save").performClick()
        assertEquals(null to 90, saved)
        rule.onNodeWithTag("time-duration-reset").performClick()
        rule.onNodeWithTag("time-duration").assertTextContains("60 Min")
        rule.onNodeWithTag("time-save").performClick()
        assertEquals(null to null, saved)
    }

    @Test fun durationNeverGoesBelowFifteen() {
        show(stayMinutes = 15)
        rule.onNodeWithTag("time-duration-minus").performClick()
        rule.onNodeWithTag("time-duration").assertTextContains("15 Min")
    }
}
