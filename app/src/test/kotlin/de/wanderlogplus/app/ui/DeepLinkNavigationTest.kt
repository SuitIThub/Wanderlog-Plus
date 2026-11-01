package de.wanderlogplus.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.ui.setup.SetupViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinkNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun coldStartDeepLinkOpensPrefilledSetupOnceSettingsLoaded() {
        var vm: SetupViewModel? = null
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SetupViewModel({ _, _ -> Result.success("a") }, "https://old.example.org", "old") { _, _ -> }.also { vm = it } as T
        }
        var hasServer by mutableStateOf<Boolean?>(null)
        var pending by mutableStateOf<ServerSettings?>(ServerSettings("https://wlp.example.org", "secret"))
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(
                    hasServer = hasServer,
                    setupViewModelFactory = factory,
                    pendingSetup = pending,
                    onPendingSetupConsumed = { pending = null },
                )
            }
        }
        rule.runOnIdle { hasServer = true }
        rule.waitForIdle()
        rule.onNodeWithText("Server einrichten").assertIsDisplayed()
        rule.onNodeWithText("https://wlp.example.org").assertIsDisplayed()
        assertEquals("secret", vm?.state?.value?.token)
        assertNull(pending)
    }

    /** Settings already loaded on the first frame: the effect must not navigate before NavHost has set its graph. */
    @Test fun deepLinkOnFirstCompositionDoesNotCrash() {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SetupViewModel({ _, _ -> Result.success("a") }) { _, _ -> } as T
        }
        var pending by mutableStateOf<ServerSettings?>(ServerSettings("https://wlp.example.org", "secret"))
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(hasServer = true, setupViewModelFactory = factory, pendingSetup = pending, onPendingSetupConsumed = { pending = null })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("https://wlp.example.org").assertIsDisplayed()
    }
}
