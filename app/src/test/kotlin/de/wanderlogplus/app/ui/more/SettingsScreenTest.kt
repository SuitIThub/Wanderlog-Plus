package de.wanderlogplus.app.ui.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.data.TripZone
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private var resets = 0

    private fun show() = rule.setContent {
        WanderlogPlusTheme {
            SettingsScreen(
                SettingsInfo("https://s.example", "supersecret1234", "dev-abc123"),
                SyncStatus(pending = 2),
                onChangeServer = {}, onSyncNow = {}, onResetSync = { resets++ },
            )
        }
    }

    @Test fun tokenIsMaskedAndStatusShown() {
        show()
        rule.onNodeWithText("Token: ••••1234").assertIsDisplayed()
        rule.onNodeWithText("supersecret1234", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Wartende Änderungen: 2").assertIsDisplayed()
        rule.onNodeWithText("Geräte-ID: dev-abc123").assertIsDisplayed()
        assertEquals("••••", maskToken("abc"))
    }

    @Test fun resetNeedsConfirmation() {
        show()
        rule.onNodeWithText("Sync-Zustand zurücksetzen").performClick()
        assertEquals(0, resets)
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(0, resets)
        rule.onNodeWithText("Sync-Zustand zurücksetzen").performClick()
        rule.onNodeWithText("Zurücksetzen").performClick()
        assertEquals(1, resets)
    }

    @Test fun tripZoneToggleCallsCallback() {
        var picked: TripZone? = null
        rule.setContent {
            WanderlogPlusTheme {
                SettingsScreen(
                    SettingsInfo(null, null, ""), SyncStatus(),
                    onChangeServer = {}, onSyncNow = {}, onResetSync = {},
                    tripZone = TripZone.JAPAN, onTripZone = { picked = it },
                )
            }
        }
        rule.onNodeWithText("Gerätezeit verwenden").performClick()
        assertEquals(TripZone.DEVICE, picked)
    }
}
