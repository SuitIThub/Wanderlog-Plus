package de.wanderlogplus.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
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

    @Test fun prefilledStateCarriesUrlAndTokenButNeedsNewTest() {
        val vm = SetupViewModel({ _, _ -> Result.success("a") }, "http://192.168.1.5:8080", "tok") { _, _ -> }
        assertEquals("http://192.168.1.5:8080", vm.state.value.url)
        assertEquals("tok", vm.state.value.token)
        assertEquals(false, vm.state.value.canSave)
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

    @Test fun prefillFillsFieldsWithoutSaving() {
        val vm = SetupViewModel({ _, _ -> Result.success("alice") }) { url, token -> saved += url to token }
        rule.setContent { WanderlogPlusTheme { SetupScreen(vm, onSaved = {}) } }
        vm.prefill(de.wanderlogplus.app.data.ServerSettings("https://wlp.example.org", "secret"))
        rule.waitForIdle()
        rule.onNodeWithText("https://wlp.example.org").assertIsDisplayed()
        assertEquals("secret", vm.state.value.token)
        assertEquals(false, vm.state.value.canSave)
        assertEquals(0, saved.size)
    }

    @Test fun invalidScanShowsMessageAndCancelDoesNothing() {
        val vm = SetupViewModel({ _, _ -> Result.success("alice") }) { _, _ -> }
        rule.setContent { WanderlogPlusTheme { SetupScreen(vm, onSaved = {}) } }
        rule.onNodeWithTag("setup-scan").assertIsDisplayed()
        vm.onScanned(null)
        rule.waitForIdle()
        rule.onAllNodesWithText("Kein gültiger Einrichtungs-Code").assertCountEquals(0)
        vm.onScanned("hello")
        rule.waitForIdle()
        rule.onNodeWithText("Kein gültiger Einrichtungs-Code").assertIsDisplayed()
    }

    private val old = de.wanderlogplus.app.data.ServerSettings("https://old.example.org", "oldtok")

    private fun showWithSaved(): SetupViewModel {
        val vm = SetupViewModel({ _, _ -> Result.success("alice") }, old.url, old.token, savedServer = { old }) { url, token -> saved += url to token }
        rule.setContent { WanderlogPlusTheme { SetupScreen(vm, onSaved = {}) } }
        return vm
    }

    @Test fun differentServerShowsWarning() {
        val vm = showWithSaved()
        vm.prefill(de.wanderlogplus.app.data.ServerSettings("https://evil.example.com", "x"))
        rule.waitForIdle()
        rule.onNodeWithText(
            "Achtung: Dieser Link richtet einen anderen Server ein (evil.example.com). Beim Speichern werden alle Reisedaten dorthin übertragen.",
        ).assertIsDisplayed()
    }

    @Test fun sameServerShowsNoWarning() {
        val vm = showWithSaved()
        vm.prefill(de.wanderlogplus.app.data.ServerSettings("https://old.example.org/", "newtok"))
        rule.waitForIdle()
        rule.onNodeWithTag("setup-other-server").assertDoesNotExist()
    }

    @Test fun savingDifferentServerAsksForConfirmation() {
        val vm = showWithSaved()
        vm.prefill(de.wanderlogplus.app.data.ServerSettings("https://evil.example.com", "x"))
        rule.onNodeWithText("Verbindung testen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Verbunden als alice").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Speichern").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Server wechseln?").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, saved.size)
        rule.onNodeWithText("Abbrechen").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Server wechseln?").assertCountEquals(0)
        assertEquals(0, saved.size)
        rule.onNodeWithText("Speichern").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Wechseln").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Wechseln").performClick()
        rule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals("https://evil.example.com" to "x", saved.single())
    }

    @Test fun savingSameServerNeedsNoConfirmation() {
        val vm = showWithSaved()
        rule.onNodeWithText("Verbindung testen").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Verbunden als alice").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Speichern").performClick()
        rule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(old.url to old.token, saved.single())
        assertEquals(false, vm.state.value.confirmSwitch)
    }
}
