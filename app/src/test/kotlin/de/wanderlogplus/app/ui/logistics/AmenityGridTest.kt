package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.Amenity
import de.wanderlogplus.shared.model.AmenityGroup
import de.wanderlogplus.shared.model.PropertyInfo
import de.wanderlogplus.shared.model.RoomInfo
import de.wanderlogplus.shared.model.StayInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class AmenityGridTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun itemsFindTheirGroupAndIcon() {
        assertEquals(AmenityCategory.TECH, AmenityCategories.of("Sonstiges", "Kostenloses WLAN"))
        assertEquals(AmenityCategory.BATH, AmenityCategories.of("Zimmer", "Privates Rotenburo"))
        assertEquals(AmenityCategory.FOOD, AmenityCategories.of("Komfort", "Wasserkocher mit Grüntee"))
        assertEquals(AmenityCategory.SERVICE, AmenityCategories.of("Allgemein", "Gepäckaufbewahrung"))
        assertEquals(AmenityCategory.SLEEP, AmenityCategories.of("Allgemein", "Yukata"))
        // Unknown item: the group name decides, else "Sonstiges".
        assertEquals(AmenityCategory.BATH, AmenityCategories.of("Bad", "Bambuskorb"))
        assertEquals(AmenityCategory.OTHER, AmenityCategories.of("Extras", "Bambuskorb"))
        assertEquals("wifi", AmenityCategories.iconKey("Kostenloses WLAN"))
        assertEquals("hottub", AmenityCategories.iconKey("Onsen im Haus"))
        assertEquals("kettle", AmenityCategories.iconKey("Wasserkocher"))
        assertEquals("check", AmenityCategories.iconKey("Bambuskorb"))
    }

    @Test
    fun panelsRegroupAndDropDoubles() {
        val groups = listOf(
            AmenityGroup("Technik", listOf(Amenity("WLAN"), Amenity("Klimaanlage"))),
            AmenityGroup("Sonstiges", listOf(Amenity("Onsen", "24 h"), Amenity("WLAN"), Amenity("Bambuskorb"))),
        )
        val panels = AmenityCategories.panels(groups)
        assertEquals(listOf(AmenityCategory.BATH, AmenityCategory.TECH, AmenityCategory.OTHER), panels.map { it.category })
        assertEquals(listOf("WLAN", "Klimaanlage"), panels.first { it.category == AmenityCategory.TECH }.items.map { it.name })
        assertEquals("24 h", panels.first().items.single().note)
    }

    @Test
    fun factsComeFirst() {
        val info = StayInfo(room = RoomInfo(type = "Japanisches Zimmer", sizeSqm = 24.0, beds = "2 Futons", bath = "eigenes Bad", view = "Garten", maxGuests = 2), property = PropertyInfo(meals = "Frühstück"))
        assertEquals(
            listOf("Japanisches Zimmer", "24 m²", "2 Futons", "eigenes Bad", "Garten", "bis 2 Personen", "Frühstück"),
            stayFacts(info).map { it.text },
        )
    }

    @Test
    fun gridShowsPanelsAndFoldsLongOnes() {
        val many = (1..9).map { Amenity("Ding $it") }
        val info = StayInfo(
            room = RoomInfo(type = "Twin", amenities = listOf(AmenityGroup("Bad", listOf(Amenity("Rotenburo", "privat"))))),
            property = PropertyInfo(amenities = listOf(AmenityGroup("Extras", many))),
        )
        rule.setContent { WanderlogPlusTheme { AmenityPanels(info) } }
        rule.onNodeWithTag("amenities-room").assertExists()
        rule.onNodeWithTag("amenity-panel-property-OTHER").assertExists()
        rule.onNodeWithText("Rotenburo").assertExists()
        rule.onNodeWithText("privat").assertExists()
        rule.onNodeWithText("Ding 6").assertExists()
        rule.onNodeWithText("Ding 7").assertDoesNotExist()
        rule.onNodeWithText("+ 3 weitere").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Ding 9").assertExists()
    }

    @Test
    fun longFactsSplitIntoTitleAndNote() {
        assertEquals("Für 2 Erwachsene gebucht" to "Bettart laut Buchungsbestätigung nicht angegeben", splitText("Für 2 Erwachsene gebucht; Bettart laut Buchungsbestätigung nicht angegeben"))
        assertEquals("Zimmer mit Open-Air-Bad" to "Hauptgebäude", splitText("Zimmer mit Open-Air-Bad (Hauptgebäude)"))
        assertEquals("Flussblick" to "", splitText("Flussblick"))
        val facts = stayFacts(StayInfo(room = RoomInfo(beds = "Für 2 Erwachsene gebucht; Bettart offen")))
        assertEquals(StayFact("bed", "Für 2 Erwachsene gebucht", "Bettart offen"), facts.single())
    }

    @Test
    fun longTilesTakeTheWholeRow() {
        val rows = gridRows(listOf("a", "b", "c" + "x".repeat(70), "d", "e"), columns = 2) { it.length > 60 }
        assertEquals(listOf(listOf("a", "b"), listOf("c" + "x".repeat(70)), listOf("d", "e")), rows)
    }

    @Test
    fun longTextsAreNotCutOff() {
        val long = "Gebucht: Hida-Rind-Bankettküche mit japanischem Frühstück und japanischem Abendessen im Speisesaal"
        val note = "laut Hotelwebsite in allen Gästezimmern; Booking.com nennt nur die Lobby, das Hotel aber alle Zimmer"
        val info = StayInfo(
            room = RoomInfo(type = "Japanisches Zimmer", amenities = listOf(AmenityGroup("Technik", listOf(Amenity("Kostenloses WLAN", note))))),
            property = PropertyInfo(meals = long),
        )
        rule.setContent { WanderlogPlusTheme { androidx.compose.foundation.layout.Column { StayFactTiles(stayFacts(info)); AmenityPanels(info) } } }
        listOf(long, note).forEach { t ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithText(t).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val input = results.single().layoutInput
            assertEquals(t, Int.MAX_VALUE, input.maxLines)
            org.junit.Assert.assertNotEquals(t, androidx.compose.ui.text.style.TextOverflow.Ellipsis, input.overflow)
        }
    }
}
