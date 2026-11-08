package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TransitSmartTest {
    @get:Rule val rule = createComposeRule()

    private val hotel = TransitPoint(35.674, 139.737, "Hotel Akasaka")
    private val mitsuke = TransitStation("赤坂見附", "Akasaka-mitsuke", 35.677, 139.737)
    private val naka = TransitStation("中津川", "Nakatsugawa", 35.50, 137.50)
    private val akasaka = TransitStation("赤坂", "Akasaka", 35.672, 139.7365)

    private fun route(from: TransitStation, dep: String, best: Boolean, access: Int) = TransitRoute(
        dep, "11:30", 130, transfers = 1, date = "2027-05-20",
        legs = listOf(TransitLeg("銀座線", "Ginza-Linie", from = from, to = naka, departure = dep, arrival = "11:30")),
        accessMeters = access, accessMinutes = access / 60, egressMeters = 1200, egressMinutes = 20, doorMinutes = 170, score = 191, best = best,
    )

    private val result = TransitResult(
        mitsuke, naka, "2027-05-20T09:00", walkToStationMeters = 700, walkFromStationMeters = 1200, smart = true,
        routes = listOf(route(mitsuke, "09:20", true, 700), route(akasaka, "09:25", false, 300)),
    )

    @Test
    fun smartOnlyWithAPlace() {
        val place = TransitForm(date = "2027-05-20", time = "09:00", fromPoint = hotel, fromIsPlace = true, toStation = "中津川")
        assertTrue(requestFor(place).smart)
        assertFalse(requestFor(place.copy(fromStation = "赤坂")).smart)
        assertFalse(requestFor(place.copy(smart = false)).smart)
        assertTrue(smartApplies(place))
        assertFalse(smartApplies(place.copy(fromStation = "赤坂")))
    }

    @Test
    fun placesAreNotPrefilledWithAStation() {
        val api = SyncApi(HttpClient(MockEngine {
            respond("""{"stations":[{"station":{"nameJa":"赤坂","name":"Akasaka"},"meters":300}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://s", "tok")
        val vm = TransitViewModel({ api }, hotel, null, "2027-05-20", "09:00", null)
        val end = System.currentTimeMillis() + 3_000
        while (vm.state.value.nearFrom.isEmpty() && System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        assertEquals(1, vm.state.value.nearFrom.size)
        assertEquals("", vm.state.value.form.fromStation)
    }

    @Test
    fun cardShowsBalanceAndWalks() {
        rule.setContent { WanderlogPlusTheme { TransitRouteCard(result, result.routes[0], 0, Color.Blue) } }
        rule.onNodeWithTag("transit-best-0", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ab Akasaka-mitsuke", substring = true).assertExists()
        rule.onNodeWithText("Tür zu Tür", substring = true).assertExists()
    }

    @Test
    fun chosenRouteKeepsItsOwnStations() {
        val c = chosenRoute(result, result.routes[1])
        assertEquals("赤坂", c.fromStation.nameJa)
        assertEquals(300, c.walkToStationMeters)
        assertEquals(1, c.routes.size)
    }
}
