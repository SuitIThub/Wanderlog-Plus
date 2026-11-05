package de.wanderlogplus.app.ui.transit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.graphics.Color
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.design.LegRow
import de.wanderlogplus.app.ui.trip.DayMapScreen
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TransitFee
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStationNear
import de.wanderlogplus.shared.sync.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TransitTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val tokyo = TransitStation("東京", "Tokyo", 35.6812, 139.7671)
    private val nagoya = TransitStation("名古屋", "Nagoya", 35.1709, 136.8815)
    private val naka = TransitStation("中津川", "Nakatsugawa", 35.5006, 137.5028)
    private val route = TransitRoute(
        departure = "08:12", arrival = "10:48", durationMinutes = 156, ridingMinutes = 144, transfers = 1,
        fareYen = 12530, baseFareYen = 7150, expressFareYen = 5380,
        fees = listOf(TransitFee("自由席", "Zuschlag, freier Sitz", 4180), TransitFee("自由席", "Zuschlag, freier Sitz", 1200)),
        distanceKm = 445.9, fastest = true, fewestTransfers = true,
        legs = listOf(
            TransitLeg("ＪＲ新幹線のぞみ15号", "JR Shinkansen Nozomi 15", "博多行", "Richtung 博多", false, tokyo, nagoya, "08:12", "09:48", "18", "16"),
            TransitLeg("ＪＲ特急しなの7号", "JR Ltd. Exp. Shinano 7", "長野行", "Richtung 長野", false, nagoya, naka, "10:00", "10:48", "10", null),
        ),
    )
    private val result = TransitResult(tokyo, naka, "2027-04-02T09:00", routes = listOf(route), walkToStationMeters = 450, walkFromStationMeters = 1200)

    @Test fun formatting() {
        assertEquals("12.530 ¥", yen(12530))
        assertEquals("08:12–10:48 · 2 Std 36 Min · 12.530 ¥ · 1× umsteigen", transitSummary(route))
        assertEquals("09:05", normalizeTime("9:5"))
        assertNull(normalizeTime("25:00"))
        assertEquals("2027-05-20T07:30", transitDateTime("2027-05-20", "7.30"))
        assertEquals("2027-05-20T09:00", transitDateTime("2027-05-20", null))
        assertEquals("Tokyo (東京)", stationLabel(tokyo))
        assertEquals("1,2 km", walkLabel(1200))
    }

    @Test fun aConnectionBecomesALegWithWalksAndStationPath() {
        val from = LatLngD(35.684, 139.770)
        val to = LatLngD(35.49, 137.50)
        val leg = LegRepository.transitRoute(from, to, result)!!
        assertEquals(156 * 60L + (1650 / 1.2).toLong(), leg.durationSeconds)
        assertEquals(445_900L + 1650, leg.distanceMeters)
        assertEquals(listOf(from, LatLngD(35.6812, 139.7671), LatLngD(35.1709, 136.8815), LatLngD(35.5006, 137.5028), to), leg.path)
        assertNull(LegRepository.transitRoute(from, to, result.copy(routes = emptyList())))
    }

    @Test fun theWayOfAnItemIsFromThePlaceBefore() {
        val w = de.wanderlogplus.app.ui.trip.dayWay(Fixtures.trip(), "2027-04-02", "i-3")!!
        assertEquals("09:00", w.fromTime)
        assertNull(de.wanderlogplus.app.ui.trip.dayWay(Fixtures.trip(), "2027-04-02", "nothing"))
    }

    @Test fun chosenConnectionInTheDayLegWithPriceAndButtons() {
        var opened = 0
        rule.setContent {
            WanderlogPlusTheme {
                LegRow(
                    tag = "i-3", from = LatLngD(35.684, 139.770), to = LatLngD(35.49, 137.5), mode = TravelMode.TRANSIT,
                    onMode = {}, accent = Color.Blue, onOpenLink = null,
                    repository = LegRepository({ null }, tmp.root), datetime = "2027-04-02T09:00", chosen = result,
                    onTransitDetails = { opened++ },
                )
            }
        }
        rule.onNodeWithText("08:12–10:48 · 2 Std 36 Min · 12.530 ¥ · 1× umsteigen").assertExists()
        rule.onNodeWithTag("leg-transit-open").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
        rule.onNodeWithText("08:12–10:48 · 2 Std 36 Min · 12.530 ¥ · 1× umsteigen").performSemanticsAction(SemanticsActions.OnClick) // expand
        rule.onNodeWithTag("leg-transit").assertExists()
        rule.onNodeWithText("JR Shinkansen Nozomi 15").assertExists()
        rule.onNodeWithText("Tokyo (東京), Gleis 18  →  Nagoya (名古屋), Gleis 16").assertExists()
        rule.onNodeWithText("450 m zu Fuß zum Bahnhof Tokyo (東京)").assertExists()
        rule.onNodeWithText("Fahrpreis 7.150 ¥ · Zuschlag, freier Sitz 4.180 ¥ · Zuschlag, freier Sitz 1.200 ¥ · pro Erwachsenem").assertExists()
        rule.onNodeWithText("gewählt").assertExists()
        rule.onNodeWithTag("leg-transit-details").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, opened)
    }

    @Test fun dayMapOffersTheQueryForTransitWays() {
        val snap = Fixtures.trip().let { s -> s.copy(dayItems = s.dayItems.map { if (it.id == "i-3") it.copy(legMode = TravelMode.TRANSIT, legTransit = result) else it }) }
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { DayMapScreen(snap, "2027-04-02", onBack = {}, repository = null, onTransitDetails = { opened = it }) } }
        rule.onNodeWithTag("day-map-transit-2").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("i-3", opened)
    }

    @Test fun screenEditsSearchesAndTakesAConnectionOver() {
        var state by mutableStateOf(
            TransitScreenState(
                form = TransitForm("東京", "", "2027-04-02", "09:00"),
                nearTo = listOf(TransitStationNear(naka, 1200), TransitStationNear(TransitStation("美乃坂本", "Minosakamoto"), 2500)),
            ),
        )
        var searched = 0
        var chosen: TransitResult? = null
        rule.setContent {
            WanderlogPlusTheme {
                TransitScreen(
                    "Hotel → Wanderweg", state, Color.Blue, chosen = null,
                    onEdit = { f -> state = state.copy(form = f(state.form)) },
                    onSearch = { searched++; state = state.copy(result = result) },
                    onChoose = { chosen = it }, onClear = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithTag("transit-to-near-1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("美乃坂本", state.form.toStation)
        rule.onNodeWithTag("transit-type-arrive").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("transit-pref-cheapest").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("transit-shinkansen").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("arrive", state.form.searchType)
        assertEquals("cheapest", state.form.preference)
        assertEquals(false, state.form.useShinkansen)
        rule.onNodeWithTag("transit-search").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, searched)
        rule.onNodeWithTag("transit-choose-0").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(route), chosen!!.routes)
    }

    @Test fun viewModelPrefillsNearestStationsAndAsksForFreshData() {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        var sent = ""
        val api = SyncApi(HttpClient(MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/transit/stations") ->
                    respond(if (req.url.parameters["lat"]!!.startsWith("35.6")) """{"stations":[{"station":{"nameJa":"東京","name":"Tokyo"},"meters":450}]}"""
                    else """{"stations":[{"station":{"nameJa":"中津川","name":"Nakatsugawa"},"meters":1200}]}""", HttpStatusCode.OK, json)
                else -> {
                    sent = String(req.body.toByteArray())
                    respond("""{"error":"no_station"}""", HttpStatusCode.NotFound, json)
                }
            }
        }), "https://s", "tok")
        val vm = TransitViewModel({ api }, TransitPoint(35.684, 139.77), TransitPoint(35.49, 137.5), "2027-05-20", "8:15", null)
        waitUntil { vm.state.value.form.toStation.isNotEmpty() }
        assertEquals("東京", vm.state.value.form.fromStation)
        assertEquals("中津川", vm.state.value.form.toStation)
        assertEquals("08:15", vm.state.value.form.time)
        vm.edit { it.copy(time = "nachmittags") }
        vm.search()
        assertTrue(vm.state.value.message!!.startsWith("Uhrzeit"))
        vm.edit { it.copy(time = "14:00") }
        vm.search()
        waitUntil { !vm.state.value.searching }
        assertTrue(sent.contains("\"fresh\":true") && sent.contains("2027-05-20T14:00") && sent.contains("中津川"))
        assertTrue(vm.state.value.message!!.contains("keinen Bahnhof"))
    }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }
}
