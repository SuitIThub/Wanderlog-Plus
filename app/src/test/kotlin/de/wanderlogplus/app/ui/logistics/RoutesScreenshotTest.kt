package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Endpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records a train with a stopover (list + detail) and a day with an expanded leg. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1200dp-xxhdpi")
class RoutesScreenshotTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun snapWithStopover() = Fixtures.trip().let { s ->
        s.copy(trains = s.trains.map { it.copy(via = listOf(Endpoint("Mittelstadt Hbf", lat = 34.86, lng = 135.58, time = "10:25"))) })
    }

    @Test fun trainWithStopover() {
        val snap = snapWithStopover()
        rule.setContent {
            WanderlogPlusTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LogisticsDetailScreen(snap, de.wanderlogplus.shared.sync.EntityType.TRAIN, "tr-1", onBack = {}, onEdit = { _, _ -> }, onMerge = { _, _, _ -> }, onDelete = { _, _ -> })
                }
            }
        }
        rule.onRoot().recordScreenshot("routes-train-detail-light")
    }

    @Test fun dayWithLeg() {
        val base = Fixtures.trip()
        val snap = base.copy(days = base.days.map { if (it.id == "d-2") it.copy(showLegs = true) else it })
        val repo = LegRepository(
            api = {
                SyncApi(
                    HttpClient(MockEngine { respond("""{"durationSeconds":1500,"distanceMeters":1800,"polyline":"_p~iF~ps|U_ulLnnqC"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
                    "https://s", "tok",
                )
            },
            dir = tmp.newFolder(),
        )
        rule.setContent {
            WanderlogPlusTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalLegRepository provides repo) {
                        DayDetailScreen(snap, "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {}, onShowLegs = { _, _ -> }, onOpenLink = {})
                    }
                }
            }
        }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("25 Min · 1,8 km").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("25 Min · 1,8 km").performClick()
        rule.onRoot().recordScreenshot("routes-day-leg-dark")
    }
}
