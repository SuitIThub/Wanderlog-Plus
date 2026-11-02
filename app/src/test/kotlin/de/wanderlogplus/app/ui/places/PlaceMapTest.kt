package de.wanderlogplus.app.ui.places

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PlacePoi
import de.wanderlogplus.shared.model.PoiKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlaceMapTest {
    @get:Rule val rule = createComposeRule()

    private val dossier = PlaceDossier(
        pois = listOf(
            PlacePoi("Nordtor", PoiKinds.ENTRANCE, 35.021, 135.781, "Weniger Andrang"),
            PlacePoi("Ohne Lage", PoiKinds.VIEWPOINT),
            PlacePoi("Bahnhof Bergstadt-Ost", "STATION", 35.018, 135.776),
        ),
    )

    private fun snapWithPois() = Fixtures.trip().let { s ->
        s.copy(savedPlaces = s.savedPlaces.map { if (it.id == "s-tempel") it.copy(dossier = dossier) else it })
    }

    @Test fun pointsPutThePlaceFirstAndDropSpotsWithoutCoordinates() {
        val s = snapWithPois()
        val points = placeMapPoints(s.places["p-tempel"], dossier)
        assertEquals(listOf(PLACE_POINT_ID, "poi-0", "poi-2"), points.map { it.id })
        assertEquals(null, points[0].kind)
        assertEquals(PoiKinds.STATION, points[2].kind) // normalised
        assertTrue(placeMapPoints(null, null).isEmpty())
        assertTrue(poiSymbol(PoiKinds.ENTRANCE) != poiSymbol(PoiKinds.STATION))
    }

    @Test fun detailEmbedsMapAndOpensFullscreen() {
        var opened = 0
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(snapWithPois(), "s-tempel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, onOpenMap = { opened++ })
            }
        }
        rule.onNodeWithTag("place-map-card").assertExists()
        rule.onNodeWithText("+ 2 Punkte aus der Recherche").assertExists()
        rule.onNodeWithTag("place-map-fullscreen").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun noMapWithoutCallback() {
        rule.setContent {
            WanderlogPlusTheme { PlaceDetailScreen(snapWithPois(), "s-tempel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }) }
        }
        rule.onNodeWithTag("place-map-card").assertDoesNotExist()
    }

    @Test fun fullscreenListsPlaceAndSpotsWithKinds() {
        var back = 0
        rule.setContent { WanderlogPlusTheme { PlaceMapScreen(snapWithPois(), "s-tempel", onBack = { back++ }) } }
        rule.onNodeWithText("Der Ort selbst").assertExists()
        rule.onNodeWithText("Nordtor").assertExists()
        rule.onNodeWithText("Eingang").assertExists()
        rule.onNodeWithText("Bahnhof/Haltestelle").assertExists()
        rule.onNodeWithText("Ohne Lage").assertDoesNotExist()
        rule.onNodeWithTag("map-point-poi-0").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("place-map-back").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, back)
    }

    @Test fun researchTabListsSpots() {
        rule.setContent {
            WanderlogPlusTheme { PlaceDetailScreen(snapWithPois(), "s-tempel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }) }
        }
        rule.onNodeWithTag("tab-research").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("PUNKTE VOR ORT").assertExists()
        rule.onNodeWithText("🚪 Nordtor (Eingang) – Weniger Andrang").assertExists()
    }
}
