package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.AppNavigation
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Day view → "Verbindung abfragen" through the real navigation; the nearby stations are filled in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitFlowTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val edit: EditFn = { _ -> }

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    @Test fun dayLegOpensTheTransitScreen() {
        val snap = Fixtures.trip().let { s ->
            s.copy(
                days = s.days.map { if (it.id == "d-2") it.copy(showLegs = true) else it },
                dayItems = s.dayItems.map { if (it.id == "i-3") it.copy(legMode = TravelMode.TRANSIT) else it },
            )
        }
        val flow = MutableStateFlow<TripSnapshot?>(snap)
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val api = SyncApi(HttpClient(MockEngine { respond("""{"stations":[{"station":{"nameJa":"清水五条","name":"Kiyomizu-Gojō"},"meters":300}]}""", HttpStatusCode.OK, json) }), "https://s", "tok")
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(
                    hasServer = true,
                    snapshot = snap,
                    tripViewModelFactory = factory { TripViewModel({ flow }, edit, scope) },
                    clock = { LocalDateTime.parse("2027-04-02T10:00") },
                    transitApi = { api },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("nav-reise").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("trip-list").performScrollToNode(hasTestTag("day-2027-04-02"))
        rule.onNodeWithTag("day-2027-04-02").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("leg-transit-open", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("transit-screen").assertExists()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("transit-from-near-0")).fetchSemanticsNodes().isNotEmpty() }
    }
}
