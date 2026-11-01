package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.SyncStatusBar
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.setup.SetupScreen
import de.wanderlogplus.app.ui.setup.SetupViewModel
import de.wanderlogplus.app.ui.share.ShareTargetScreen
import de.wanderlogplus.app.ui.share.shareTargets
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Note
import de.wanderlogplus.shared.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records Mehr, Notizen, Einstellungen, Import, Konflikte, Setup and Teilen-Ziel, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xxhdpi")
class MoreScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun suffix(dark: Boolean) = if (dark) "dark" else "light"

    private fun shot(name: String, dark: Boolean, before: () -> Unit = {}, content: @Composable () -> Unit) {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        before()
        rule.waitForIdle()
        rule.onRoot().recordScreenshot("$name-${suffix(dark)}")
    }

    private fun conflictSnapshot(): TripSnapshot {
        val store = InMemoryEntityStore()
        fun seed(id: String, title: String, other: String) {
            var rec = EntityRecord(EntityType.NOTE, id)
            val fields: Map<String, JsonElement> = EntityCodec.fields(Note(id, title = title, body = "")) + ("deleted" to JsonPrimitive(false))
            fields.forEach { (k, v) -> rec = rec.apply(FieldChange(EntityType.NOTE, id, k, v, Hlc(1, 0, "importer").encode()))!! }
            rec = rec.apply(FieldChange(EntityType.NOTE, id, "_conflict.title", JsonPrimitive(other), Hlc(2, 0, "importer").encode()))!!
            store.put(rec)
        }
        seed("n1", "Packliste", "Packliste Japan")
        seed("n2", "Sätze", "Lange Notiz ".repeat(20))
        return TripSnapshot.from(store.all())
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun entry(started: String, ok: Boolean = true) =
        """{"startedAt":"$started","finishedAt":"$started","ok":$ok,"created":3,"updated":2,"conflicts":1,"removed":0}"""

    private fun importVm(): ImportViewModel {
        val body = "[" + listOf(entry("2027-05-01T10:00:00Z"), entry("2027-04-20T08:30:00Z", ok = false), entry("2027-04-01T19:00:00Z")).joinToString(",") + "]"
        val api = SyncApi(HttpClient(MockEngine { respond(body, HttpStatusCode.OK, json) }), "https://s.example", "tok")
        return ImportViewModel({ api }, onImported = {})
    }

    private fun mehr(dark: Boolean) = shot("more-menu", dark) { MoreScreen(2, onOpen = {}) }

    private fun notes(dark: Boolean) = shot("more-notes", dark) { NotesScreen(Fixtures.trip(longTexts = true), onOpen = {}) }

    private fun settings(dark: Boolean) = shot("more-settings", dark) {
        SettingsScreen(
            SettingsInfo("https://wanderlog.example.org", "supersecret1234", "dev-abc123"),
            SyncStatus(lastSyncAt = 1_000_000L, pending = 2),
            onChangeServer = {}, onSyncNow = {}, onResetSync = {},
            now = 1_000_000L + 5 * 60_000L,
            notificationsAllowed = false,
        )
    }

    private fun import(dark: Boolean) = shot("more-import", dark, before = { rule.waitUntil(5_000) { rule.onAllNodesWithText("PROTOKOLL").fetchSemanticsNodes().isNotEmpty() } }) {
        val vm = importVm()
        ImportScreen(vm)
    }

    private fun conflicts(dark: Boolean) = shot("more-conflicts", dark) { ConflictsScreen(conflictSnapshot(), onKeepApp = {}, onTakeWanderlog = {}) }

    private fun setup(dark: Boolean) = shot("more-setup", dark) {
        val old = ServerSettings("https://old.example.org", "oldtok")
        val vm = SetupViewModel({ _, _ -> Result.success("alice") }, "http://sync.example.org", "tok", savedServer = { old }) { _, _ -> }
        SetupScreen(vm, onSaved = {})
    }

    private fun share(dark: Boolean) = shot("more-share", dark) {
        ShareTargetScreen(shareTargets(Fixtures.trip()), fileCount = 2, onPick = {})
    }

    private fun syncBars(dark: Boolean) = shot("more-syncbar", dark) {
        androidx.compose.foundation.layout.Column {
            SyncStatusBar(SyncStatus(running = true))
            SyncStatusBar(SyncStatus(pending = 3))
            SyncStatusBar(SyncStatus(lastSyncAt = 1_000_000L), now = 1_000_000L + 120_000L)
            SyncStatusBar(SyncStatus(lastError = "SocketTimeoutException"))
        }
    }

    @Test fun mehrLight() = mehr(false)
    @Test fun mehrDark() = mehr(true)
    @Test fun notesLight() = notes(false)
    @Test fun notesDark() = notes(true)
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
    @Test fun importLight() = import(false)
    @Test fun importDark() = import(true)
    @Test fun conflictsLight() = conflicts(false)
    @Test fun conflictsDark() = conflicts(true)
    @Test fun setupLight() = setup(false)
    @Test fun setupDark() = setup(true)
    @Test fun shareLight() = share(false)
    @Test fun shareDark() = share(true)
    @Test fun syncBarLight() = syncBars(false)
    @Test fun syncBarDark() = syncBars(true)
}
