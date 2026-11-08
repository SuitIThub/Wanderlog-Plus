package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TransitPagingTest {
    @get:Rule val rule = createComposeRule()

    private val a = TransitStation("日本橋", "Nippombashi", 34.667, 135.506)
    private val b = TransitStation("十日市町", "Tōkaichi-machi", 34.396, 132.452)

    private fun route(dep: String, arr: String, access: Int? = null, egress: Int? = null) = TransitRoute(
        dep, arr, 150, date = "2027-05-20", accessMinutes = access, egressMinutes = egress,
        legs = listOf(TransitLeg("x", "Nozomi", from = a, to = b, departure = dep, arrival = arr)),
    )

    @Test
    fun laterStartsAfterTheEarliestDeparture() {
        val r = TransitResult(a, b, "2027-05-20T09:00", routes = listOf(route("09:20", "11:40"), route("09:04", "11:32")))
        assertEquals("2027-05-20" to "09:05", laterStart(r))
        // Smart search: the walk to the station is part of it.
        val smart = r.copy(routes = listOf(route("09:04", "11:32", access = 3)))
        assertEquals("2027-05-20" to "09:02", laterStart(smart))
    }

    @Test
    fun earlierEndsBeforeTheEarliestArrival() {
        val r = TransitResult(a, b, "2027-05-20T09:00", routes = listOf(route("09:20", "11:40", egress = 3), route("09:04", "11:32", egress = 3)))
        assertEquals("2027-05-20" to "11:34", earlierEnd(r))
        // An arrival after midnight counts on the next day.
        val late = TransitResult(a, b, "2027-05-20T23:00", routes = listOf(route("23:30", "00:20")))
        assertEquals("2027-05-21" to "00:19", earlierEnd(late))
    }

    @Test
    fun cardOpensTheMap() {
        val r = TransitResult(a, b, "2027-05-20T09:00", routes = listOf(route("09:04", "11:32")), fromPlace = TransitPoint(34.666, 135.507, "Hotel"))
        rule.setContent { WanderlogPlusTheme { TransitRouteCard(r, r.routes[0], 0, Color.Blue) } }
        rule.onNodeWithTag("transit-map-view-0").assertDoesNotExist()
        rule.onNodeWithTag("transit-map-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("transit-map-view-0").assertExists()
    }

    @Test
    fun earlierAndLaterButtons() {
        val r = TransitResult(a, b, "2027-05-20T09:00", routes = listOf(route("09:04", "11:32")))
        var earlier = 0
        var later = 0
        rule.setContent {
            WanderlogPlusTheme {
                TransitScreen("Weg", TransitScreenState(form = TransitForm(date = "2027-05-20"), result = r), Color.Blue, chosen = null,
                    onEdit = {}, onSearch = {}, onChoose = {}, onClear = {}, onBack = {}, onEarlier = { earlier++ }, onLater = { later++ })
            }
        }
        rule.onNodeWithTag("transit-earlier").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("transit-later").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, earlier)
        assertEquals(1, later)
    }
}
