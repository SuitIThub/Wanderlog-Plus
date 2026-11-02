package de.wanderlogplus.app.ui.reels

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.AppNavigation
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.GeoSearch
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The whole "Position suchen" path through the real navigation: reel result -> place search -> hits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReelPositionFlowTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val edit: EditFn = { _ -> }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

    @Test fun findPositionShowsHits() {
        val queries = mutableListOf<String>()
        val geo = GeoSearch(HttpClient(MockEngine { req ->
            queries += req.url.toString()
            respond(
                """{"features":[{"geometry":{"type":"Point","coordinates":[139.7178,35.7295]},"properties":{"name":"Sunshine 60","city":"Tokyo","country":"Japan"}}]}""",
                HttpStatusCode.OK, json,
            )
        }))
        val api = SyncApi(HttpClient(MockEngine {
            respond(
                """{"id":"j1","kind":"reel","createdAt":0,"status":"DONE","candidates":[{"name":"Sunshine 60 Observatory","city":"Tokio","positionIssue":"not_found"}]}""",
                HttpStatusCode.OK, json,
            )
        }), "https://s", "tok")
        val flow = MutableStateFlow<TripSnapshot?>(Fixtures.trip())
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(
                    hasServer = true,
                    snapshot = Fixtures.trip(),
                    placesViewModelFactory = factory { PlacesViewModel({ flow }, edit, scope) },
                    moreViewModelFactory = factory { de.wanderlogplus.app.ui.more.MoreViewModel({ flow }, edit, scope) },
                    reelsViewModelFactory = factory {
                        ReelsViewModel({ flow }, edit, { api }, object : ImportedJobs {
                            override fun contains(id: String) = false
                            override fun add(id: String) {}
                        }, scope, pollMillis = 10)
                    },
                    geoSearch = geo,
                    pendingReelJob = "j1",
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("reel-find-position-0") }
        rule.onNodeWithTag("reel-find-position-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(5_000) { rule.onAllNodesWithTagExists("search-hit-0") }
        assertTrue(queries.isNotEmpty())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
