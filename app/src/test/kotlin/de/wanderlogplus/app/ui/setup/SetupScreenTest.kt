package de.wanderlogplus.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupScreenTest {
    @get:Rule val rule = createComposeRule()

    private val saved = mutableListOf<Pair<String, String>>()

    private fun show(test: suspend (String, String) -> Result<String> = { _, _ -> Result.success("alice") }) {
        val vm = SetupViewModel(test) { url, token -> saved += url to token }
        rule.setContent { WanderlogPlusTheme { SetupScreen(vm, onSaved = {}) } }
    }

    @Test fun invalidUrlShowsErrorAndSaveStaysDisabled() {
        show()
        rule.onNodeWithText("Server-Adresse").performTextInput("ht tp://x y")
        rule.onNodeWithText("Verbindung testen").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Ungültige Server-Adresse").assertIsDisplayed()
        rule.onNodeWithText("Speichern").assertIsNotEnabled()
    }

    @Test fun insecureUrlShowsWarning() {
        show()
        rule.onNodeWithText("Server-Adresse").performTextInput("http://sync.example.org")
        rule.onNodeWithText(
            "Unverschlüsselte Verbindung über das Internet – Token und Buchungsdaten sind lesbar.",
        ).assertIsDisplayed()
    }

    @Test fun successfulTestEnablesSaveAndSavesNormalizedUrl() {
        show()
        rule.onNodeWithText("Server-Adresse").performTextInput("sync.example.org/")
        rule.onNodeWithText("Token").performTextInput("secret")
        rule.onNodeWithText("Speichern").assertIsNotEnabled()
        rule.onNodeWithText("Verbindung testen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Verbunden als alice").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Speichern").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals("https://sync.example.org" to "secret", saved.single())
    }
}
