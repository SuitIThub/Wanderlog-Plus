package de.wanderlogplus.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsAllTabsAndSwitchesToLogistik() {
        rule.setContent { WanderlogPlusTheme { AppNavigation() } }
        listOf("Heute", "Reise", "Orte", "Logistik", "Mehr").forEach {
            rule.onAllNodesWithText(it).onFirst().assertIsDisplayed()
        }
        rule.onNodeWithText("Logistik").performClick()
        rule.onNodeWithTag("screen-Logistik").assertIsDisplayed()
    }
}
