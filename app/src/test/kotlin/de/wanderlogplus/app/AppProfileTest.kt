package de.wanderlogplus.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.data.SettingsStore
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.more.SettingsInfo
import de.wanderlogplus.app.ui.more.SettingsScreen
import de.wanderlogplus.app.ui.more.debugHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class AppProfileTest {
    @get:Rule val rule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun setDebug(on: Boolean) {
        context.getSharedPreferences("app_profile", Context.MODE_PRIVATE).edit().putBoolean("debug", on).commit()
        AppProfile.resetForTests()
    }

    @After fun normalAgain() = runBlocking {
        // The stores outlive a test in this process: leave both profiles empty for the next ones.
        setDebug(true); SettingsStore(context).clearForTests()
        setDebug(false); SettingsStore(context).clearForTests()
    }

    @Test fun debugProfileUsesItsOwnNames() {
        setDebug(false)
        assertEquals("wanderlog-plus.db", AppProfile.databaseName(context))
        assertEquals("sync_state", AppProfile.scoped(context, "sync_state"))
        setDebug(true)
        assertEquals("wanderlog-plus-debug.db", AppProfile.databaseName(context))
        assertEquals("attachments-debug", AppProfile.scoped(context, "attachments"))
        assertEquals("192.168.1.20:8080", debugHost("http://192.168.1.20:8080/wlp"))
    }

    @Test fun serverConnectionsAreSeparate() = runBlocking {
        setDebug(false)
        SettingsStore(context).saveServer(ServerSettings("https://home.example", "home-token-123456"))
        setDebug(true)
        val debug = SettingsStore(context)
        assertNull(debug.server.first()) // the debug profile starts without a server
        debug.saveServer(ServerSettings("http://192.168.1.20:8080", "debug-token-123456"))
        debug.since = 42
        setDebug(false)
        val normal = SettingsStore(context)
        assertEquals("https://home.example", normal.server.first()!!.url)
        assertEquals(0L, normal.since) // sync state not shared either
        setDebug(true)
        assertEquals("http://192.168.1.20:8080", SettingsStore(context).server.first()!!.url)
        assertEquals(42L, SettingsStore(context).since)
    }

    @Test fun settingsSwitchAsksBeforeRestarting() {
        var switched: Boolean? = null
        rule.setContent {
            WanderlogPlusTheme {
                SettingsScreen(SettingsInfo("http://192.168.1.20:8080", "t", "dev"), SyncStatus(), onChangeServer = {}, onSyncNow = {}, onResetSync = {}, debugMode = false, onDebugMode = { switched = it })
            }
        }
        rule.onNodeWithTag("settings-debug").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Debug-Modus einschalten?").assertExists()
        assertNull(switched)
        rule.onNodeWithTag("settings-debug-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(true, switched)
    }
}
