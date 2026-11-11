package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class JourneyUiTest {
    @get:Rule val rule = createComposeRule()

    private val lh = Flight("f1", airlineCode = "LH", number = "716", depart = Endpoint("Frankfurt", "FRA", date = "2027-05-07", time = "13:30"), arrive = Endpoint("Haneda", "HND", date = "2027-05-08", time = "08:45"), journeyId = "J")
    private val nh = Flight("f2", airlineCode = "NH", number = "23", depart = Endpoint("Haneda", "HND", date = "2027-05-08", time = "09:30"), arrive = Endpoint("Itami", "ITM", date = "2027-05-08", time = "10:40"), journeyId = "J")
    private val bus = Train("t1", "Limousine Bus", Endpoint("Itami", date = "2027-05-08", time = "11:10"), Endpoint("Kyoto", date = "2027-05-08", time = "12:05"))
    private val old = Train("t2", "JR", Endpoint("Kyoto", date = "2027-05-10", time = "08:00"), Endpoint("Nakatsugawa", date = "2027-05-10", time = "10:05"), via = listOf(Endpoint("Nagoya", time = "08:40")))

    private val snapshot = TripSnapshot(
        Trip("t", "Japan", "2027-05-08", "2027-05-29"), emptyList(), emptyMap(), emptyList(), emptyList(),
        listOf(lh, nh), listOf(bus, old), emptyList(), emptyList(), emptyList(), emptyList(),
    )

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    @Test fun listShowsOneCardPerJourney() {
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snapshot, onOpenJourney = { opened = it }) } }
        rule.onNodeWithTag("journey-J").assertExists()
        rule.onNode(hasTestTag("logistics-f2")).assertDoesNotExist() // the second leg is inside the journey card
        rule.onNode(hasText("Umstieg 45 Min. – knapp"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("journey-J").performClick()
        assertEquals("J", opened)
    }

    @Test fun editModeAddsAtTheTopAndTheEnd() {
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snapshot, onAdd = {}) } }
        rule.onNode(hasTestTag("logistics-add")).assertDoesNotExist()
        tap("logistics-edit")
        rule.onNodeWithTag("logistics-add").assertExists()
        rule.onNodeWithTag("logistics-list").performScrollToNode(hasTestTag("logistics-add-end"))
        rule.onNodeWithTag("logistics-add-end").assertExists()
        rule.onNodeWithText("Fertig").assertExists()
    }

    @Test fun journeyTimelineInsertsLeavesAndDissolves() {
        val added = mutableListOf<Triple<EntityType, Pair<EntityType, String>?, Pair<EntityType, String>?>>()
        val left = mutableListOf<String>()
        var dissolved: String? = null
        rule.setContent {
            WanderlogPlusTheme {
                JourneyScreen(
                    snapshot, "J", onBack = {}, onOpenLeg = { _, _ -> },
                    onAddLeg = { t, p, n -> added += Triple(t, p, n) }, onJoin = {},
                    onLeave = { _, id -> left += id }, onDissolve = { dissolved = it },
                )
            }
        }
        rule.onNode(hasText("Frankfurt → Itami"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("1/2"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Umstieg 45 Min. – knapp"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("gleicher Flughafen"), useUnmergedTree = true).assertExists()

        // Without the edit mode only the legs and transfers show.
        rule.onNode(hasTestTag("journey-insert-2")).assertDoesNotExist()
        rule.onNode(hasTestTag("journey-leg-0-leave")).assertDoesNotExist()
        rule.onNode(hasTestTag("journey-dissolve")).assertDoesNotExist()
        rule.onNode(hasTestTag("journey-add-existing")).assertDoesNotExist()
        rule.onNodeWithTag("journey-edit").performScrollTo().performClick()

        rule.onNodeWithTag("journey-insert-2").performScrollTo().performClick()
        tap("journey-add-train")
        assertEquals(Triple(EntityType.TRAIN, EntityType.FLIGHT to "f2", null), added.single())

        rule.onNodeWithTag("journey-leg-0-leave").performScrollTo().performClick()
        rule.onNode(hasTestTag("journey-leg-0-up")).assertDoesNotExist() // no arrows without onMoveLeg
        assertEquals(listOf("f1"), left)
        rule.onNodeWithTag("journey-dissolve").performScrollTo().performClick()
        tap("journey-dissolve-confirm")
        assertEquals("J", dissolved)
    }

    @Test fun aNewLegIsFilledFromItsNeighboursAndJoinsTheJourney() {
        val before = newLegDraft(snapshot, null, EntityType.FLIGHT to "f1")
        assertEquals("Frankfurt" to "12:00", before.arrive.name to before.arrive.time) // 90 min before the flight
        val between = newLegDraft(snapshot, EntityType.FLIGHT to "f2", null)
        assertEquals("Itami" to "10:40", between.depart.name to between.depart.time)

        val id = newLegId("J", EntityType.FLIGHT to "f2", null)
        assertTrue(isNewId(id))
        val (b, after) = draftChanges(snapshot, EntityType.TRAIN, id, between.copy(arrive = Endpoint("Kyoto")))
        assertTrue(b.isEmpty())
        assertEquals("J", after.getValue("journeyId").jsonPrimitive.content)
    }

    @Test fun legDetailShowsItsJourneyJoinsAndSplitsOldMerges() {
        var joined: List<Pair<EntityType, String>>? = null
        var split: String? = null
        var openedJourney: String? = null
        rule.setContent {
            WanderlogPlusTheme {
                androidx.compose.foundation.layout.Column {
                    LogisticsDetailScreen(snapshot, EntityType.TRAIN, "t2", onBack = {}, onJoin = { joined = it }, onSplit = { _, id -> split = id }, onOpenJourney = { openedJourney = it })
                }
            }
        }
        rule.onNode(hasTestTag("leg-journey")).assertDoesNotExist()
        rule.onNodeWithTag("move-split").performClick()
        tap("split-confirm")
        assertEquals("t2", split)
        rule.onNodeWithTag("move-join").performClick()
        tap("join-t1-check")
        tap("join-confirm")
        assertEquals(listOf(EntityType.TRAIN to "t2", EntityType.TRAIN to "t1"), joined)
        assertEquals(null, openedJourney)
    }

    @Test fun legOfAJourneyLinksToIt() {
        var openedJourney: String? = null
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snapshot, EntityType.FLIGHT, "f2", onBack = {}, onOpenJourney = { openedJourney = it }) } }
        rule.onNode(hasText("Teil der Reise Frankfurt → Itami (2/2) · ansehen"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("leg-journey").performClick()
        assertEquals("J", openedJourney)
    }

    @Test fun journeyScreenshot() {
        val withBus = snapshot.copy(trains = listOf(bus.copy(journeyId = "J"), old))
        rule.setContent {
            WanderlogPlusTheme {
                androidx.compose.material3.Surface {
                    JourneyScreen(withBus, "J", onBack = {}, onOpenLeg = { _, _ -> }, onAddLeg = { _, _, _ -> }, onJoin = {}, onLeave = { _, _ -> }, onDissolve = {})
                }
            }
        }
        rule.onRoot().recordScreenshot("journey-light")
    }

    @Test fun arrowsMoveLegsAndTheCardShowsTheRoute() {
        val moves = mutableListOf<Pair<Int, Boolean>>()
        rule.setContent {
            WanderlogPlusTheme {
                JourneyScreen(snapshot, "J", onBack = {}, onOpenLeg = { _, _ -> }, onAddLeg = { _, _, _ -> }, onJoin = {}, onLeave = { _, _ -> }, onDissolve = {}, onMoveLeg = { i, up -> moves += i to up })
            }
        }
        rule.onNode(hasTestTag("route-fallback"), useUnmergedTree = true).assertExists() // route preview under the header
        rule.onNode(hasTestTag("journey-leg-1-up")).assertDoesNotExist()
        rule.onNodeWithTag("journey-edit").performScrollTo().performClick()
        rule.onNodeWithTag("journey-leg-1-up").performScrollTo().performClick()
        rule.onNodeWithTag("journey-leg-0-down").performScrollTo().performClick()
        assertEquals(listOf(1 to true, 0 to false), moves)
        rule.onNode(hasTestTag("journey-leg-0-up")).assertExists()
    }

    @Test fun tappingTheMapOpensItFullScreen() {
        var journeyMap = 0
        var legMap = 0
        rule.setContent {
            WanderlogPlusTheme {
                androidx.compose.foundation.layout.Column {
                    JourneyScreen(snapshot, "J", onBack = {}, onOpenLeg = { _, _ -> }, onAddLeg = { _, _, _ -> }, onJoin = {}, onLeave = { _, _ -> }, onDissolve = {}, onOpenMap = { journeyMap++ }, modifier = androidx.compose.ui.Modifier.weight(1f))
                    LogisticsDetailScreen(snapshot, EntityType.FLIGHT, "f2", onBack = {}, onOpenMap = { legMap++ }, modifier = androidx.compose.ui.Modifier.weight(1f))
                }
            }
        }
        rule.onAllNodesWithTag("route-map-open")[0].performSemanticsAction(SemanticsActions.OnClick)
        rule.onAllNodesWithTag("route-map-open")[1].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1 to 1, journeyMap to legMap)
    }

    @Test fun mapScreenShowsTheRouteAndGoesBack() {
        var back = 0
        val geo = de.wanderlogplus.app.maps.routeFor(de.wanderlogplus.client.journeys(snapshot).single(), snapshot)!!
        rule.setContent { WanderlogPlusTheme { de.wanderlogplus.app.maps.RouteMapScreen(geo, "Frankfurt → Itami", onBack = { back++ }) } }
        rule.onNodeWithText("Frankfurt → Itami").assertExists()
        rule.onNode(hasTestTag("route-fallback"), useUnmergedTree = true).assertExists() // without maps (tests, offline)
        rule.onNodeWithContentDescription("Zurück").performClick()
        assertEquals(1, back)
    }

    @Test fun journeyCardHasTheRoutePreview() {
        val geo = de.wanderlogplus.app.maps.routeFor(de.wanderlogplus.client.journeys(snapshot).single(), snapshot)
        assertTrue("FRA and HND are known airports", geo != null && geo.stops.size >= 2)
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snapshot) } }
        rule.onNode(hasTestTag("route-fallback") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("journey-J")), useUnmergedTree = true).assertExists()
    }
}
