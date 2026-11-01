package de.wanderlogplus.app.ui.more

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ImportScreenTest {
    @get:Rule val rule = createComposeRule()

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val entry = """{"startedAt":"2027-05-01T10:00:00Z","finishedAt":"2027-05-01T10:00:05Z","ok":true,"created":3,"updated":2,"conflicts":1,"removed":0}"""

    @Volatile private var imported = 0
    @Volatile private var afterImport = 0

    private fun api(importResponse: () -> Pair<HttpStatusCode, String>): SyncApi {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Post) {
                imported++
                done.set(true)
                val (s, b) = importResponse()
                respond(b, s, json)
            } else {
                respond(if (done.get()) "[$entry]" else "[]", HttpStatusCode.OK, json)
            }
        }
        return SyncApi(HttpClient(engine), "https://s.example", "tok")
    }

    private fun show(api: SyncApi?) {
        val vm = ImportViewModel({ api }, onImported = { afterImport++ })
        rule.setContent { WanderlogPlusTheme { ImportScreen(vm) } }
    }

    @Test fun successShowsNewLogEntryAndRequestsSync() {
        show(api { HttpStatusCode.OK to entry })
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Noch keine Importe").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Aus Wanderlog importieren").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("LETZTER IMPORT").fetchSemanticsNodes().isNotEmpty() }
        // the latest import shows its numbers as small tiles: value above label
        rule.onNodeWithText("3").assertIsDisplayed()
        rule.onNodeWithText("neu").assertIsDisplayed()
        rule.onNodeWithText("geändert").assertIsDisplayed()
        rule.onNodeWithText("Konflikte").assertIsDisplayed()
        rule.onNodeWithText("entfernt").assertIsDisplayed()
        assertEquals(1, imported)
        assertEquals(1, afterImport)
    }

    @Test fun notConfiguredShowsHint() {
        show(api { HttpStatusCode.BadRequest to """{"error":"import_not_configured"}""" })
        rule.onNodeWithText("Aus Wanderlog importieren").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Auf dem Server ist kein Wanderlog-Trip konfiguriert.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, afterImport)
    }

    @Test fun offlineShowsErrorMessage() {
        show(SyncApi(HttpClient(MockEngine { throw java.io.IOException("down") }), "https://s.example", "tok"))
        rule.onNodeWithText("Aus Wanderlog importieren").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Keine Verbindung zum Server.").fetchSemanticsNodes().isNotEmpty() }
    }
}
