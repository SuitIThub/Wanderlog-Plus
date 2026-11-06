package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class LogisticsManageTest {
    @get:Rule val rule = createComposeRule()

    private val snapshot = TripSnapshot(
        trip = Trip("t", "Japan", "2027-05-08", "2027-05-29"),
        regions = listOf(Region("r-kyoto", "Kyoto"), Region("r-nara", "Nara")),
        places = listOf(Place("ph", name = "Hotel Gion", lat = 35.0037, lng = 135.7788), Place("pn", name = "Todai-ji", lat = 34.689, lng = 135.8398)).associateBy { it.id },
        savedPlaces = listOf(SavedPlace("sn", "pn", "r-nara")),
        stays = listOf(Stay("st", "ph", "r-kyoto", "2027-05-18", "2027-05-22", confirmationNumber = "AB12", travelerNames = listOf("X"))),
        flights = emptyList(), trains = emptyList(), days = emptyList(), dayItems = emptyList(), notes = emptyList(), conflicts = emptyList(),
    )

    private val connection = TransitResult(
        fromStation = TransitStation("京都", "Kyoto"), toStation = TransitStation("中津川", "Nakatsugawa"), datetime = "2027-05-22T08:00",
        routes = listOf(
            TransitRoute(
                "08:12", "10:05", 113, transfers = 1, date = "2027-05-22",
                legs = listOf(
                    TransitLeg("ＪＲ新幹線のぞみ21号", "JR Shinkansen Nozomi 21", from = TransitStation("京都", "Kyoto", 34.98, 135.75), to = TransitStation("名古屋", "Nagoya"), departure = "08:12", arrival = "08:47", departurePlatform = "14"),
                    TransitLeg("徒歩", walk = true, from = TransitStation("名古屋", "Nagoya"), to = TransitStation("名古屋", "Nagoya")),
                    TransitLeg("ＪＲ特急しなの7号", "JR Ltd. Exp. Shinano 7", from = TransitStation("名古屋", "Nagoya"), to = TransitStation("中津川", "Nakatsugawa", 35.49, 137.5), departure = "09:00", arrival = "10:05"),
                ),
            ),
        ),
    )

    @Test fun aConnectionFillsTheTrain() {
        val d = draftFromTransit(MoveDraft("", "", Endpoint(), emptyList(), Endpoint()), connection)
        assertEquals("JR Shinkansen → JR Ltd. Exp.", d.operator)
        assertEquals("Nozomi 21 / Shinano 7", d.number)
        assertEquals(Endpoint("Kyoto", lat = 34.98, lng = 135.75, date = "2027-05-22", time = "08:12"), d.depart)
        assertEquals(listOf(Endpoint("Nagoya", date = "2027-05-22", time = "09:00")), d.via)
        assertEquals("10:05", d.arrive.time)
        assertEquals(connection, d.transit)

        // A new train: no "before", every field "after".
        val (before, after) = draftChanges(snapshot, EntityType.TRAIN, NEW_ID, d.copy(bookingNumber = " XY9 ", note = "Wagen 7"))
        assertTrue(before.isEmpty())
        assertEquals("Nozomi 21 / Shinano 7", after.getValue("number").jsonPrimitive.content)
        assertEquals("XY9", after.getValue("confirmationNumber").jsonPrimitive.content)
        val train = Train("x", number = "Nozomi 21", transit = connection)
        assertEquals("Gleis 14", train.platformText())
    }

    @Test fun stayRulesAndRegionFromThePosition() {
        assertEquals("Die Unterkunft braucht einen Namen", validateStay(StayDraft(checkIn = "2027-05-01", checkOut = "2027-05-02")))
        assertEquals("Der Check-out muss nach dem Check-in liegen", validateStay(StayDraft(name = "H", checkIn = "2027-05-02", checkOut = "2027-05-02")))
        assertEquals("Stornofrist bitte als JJJJ-MM-TT", validateStay(StayDraft(name = "H", checkIn = "2027-05-01", checkOut = "2027-05-02", cancellationDeadline = "1.5.")))
        assertNull(validateStay(StayDraft(name = "H", checkIn = "2027-05-01", checkOut = "2027-05-02", adults = "2")))
        // Near Todai-ji: Nara; far from everything: none.
        assertEquals("r-nara", regionNear(snapshot, 34.68, 135.83))
        assertNull(regionNear(snapshot, 43.06, 141.35))

        val save = stayToSave(snapshot, StayDraft(name = "Ryokan Nara", lat = 34.68, lng = 135.83, picked = true, checkIn = "2027-05-22", checkOut = "2027-05-24", adults = "2"), null, "p-new")
        assertEquals("p-new", save.newPlace?.id)
        assertEquals(Triple("p-new", "r-nara", 2), Triple(save.stay.placeId, save.stay.regionId, save.stay.adults))
        // Editing keeps fields the form does not show.
        val edit = stayToSave(snapshot, stayDraftOf(snapshot, "st")!!.copy(note = "Spätanreise"), snapshot.stays.single(), "unused")
        assertNull(edit.newPlace)
        assertEquals(listOf("X"), edit.stay.travelerNames)
        assertEquals("AB12", edit.stay.confirmationNumber)
    }

    @Test fun addMenuOffersTrainAndFlightAndStaysGoStraightToTheForm() {
        val added = mutableListOf<EntityType>()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snapshot, onAdd = { added += it }) } }
        rule.onNodeWithTag("logistics-add").performClick()
        rule.onNodeWithTag("logistics-add-flight").performClick()
        rule.onNodeWithTag("tab-stays").performClick()
        rule.onNodeWithTag("logistics-add").performClick()
        assertEquals(listOf(EntityType.FLIGHT, EntityType.STAY), added)
    }

    @Test fun newTrainIsSavedWithNumberAndBooking() {
        var saved: Triple<String, Map<String, JsonElement>, Map<String, JsonElement>>? = null
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsEditScreen(snapshot, EntityType.TRAIN, NEW_ID, onBack = {}, onSave = { _, id, b, a -> saved = Triple(id, b, a) }, search = null)
            }
        }
        rule.onNodeWithText("Neue Fahrt").assertIsDisplayed()
        rule.onNodeWithTag("edit-save").performClick()
        rule.onNodeWithTag("edit-error").assertIsDisplayed() // no stations yet
        rule.onNodeWithTag("edit-depart-name").performTextInput("Kyoto")
        rule.onNodeWithTag("edit-arrive-name").performTextInput("Nara")
        rule.onNodeWithTag("edit-number").performTextInput("Miyakoji 3")
        rule.onNodeWithTag("edit-booking").performScrollTo().performTextInput("Q77")
        rule.onNodeWithTag("edit-save").performClick()
        val (id, before, after) = saved!!
        assertEquals(NEW_ID, id)
        assertTrue(before.isEmpty())
        assertEquals("Miyakoji 3", after.getValue("number").jsonPrimitive.content)
        assertEquals("Q77", after.getValue("confirmationNumber").jsonPrimitive.content)
    }

    @Test fun newStayNeedsDatesAndIsSaved() {
        var saved: StayDraft? = null
        rule.setContent { WanderlogPlusTheme { StayEditScreen(snapshot, NEW_ID, onBack = {}, onSave = { _, d -> saved = d }, search = null) } }
        rule.onNodeWithTag("stay-name").performTextInput("Ryokan Nara")
        rule.onNodeWithTag("stay-save").performClick()
        rule.onNode(hasText("Check-in und Check-out bitte als JJJJ-MM-TT")).assertExists()
        rule.onNodeWithTag("stay-checkin").performTextInput("2027-05-22")
        rule.onNodeWithTag("stay-checkout").performTextInput("2027-05-24")
        rule.onNodeWithTag("stay-deadline").performScrollTo().performTextInput("2027-05-15")
        rule.onNodeWithTag("stay-save").performClick()
        assertEquals("Ryokan Nara", saved?.name)
        assertEquals("2027-05-15", saved?.cancellationDeadline)
    }

    @Test fun stayDetailCanBeEditedAndDeleted() {
        val edited = mutableListOf<Pair<EntityType, String>>()
        val deleted = mutableListOf<Pair<EntityType, String>>()
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsDetailScreen(snapshot, EntityType.STAY, "st", onBack = {}, onEdit = { t, i -> edited += t to i }, onDelete = { t, i -> deleted += t to i })
            }
        }
        rule.onNodeWithTag("move-edit").performClick()
        assertEquals(listOf(EntityType.STAY to "st"), edited)
        rule.onNode(androidx.compose.ui.test.hasTestTag("move-merge")).assertDoesNotExist()
        rule.onNodeWithTag("move-delete").performClick()
        rule.onNodeWithText("Unterkunft löschen?").assertIsDisplayed()
        rule.onNodeWithTag("delete-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(EntityType.STAY to "st"), deleted)
    }

    @Test fun classSeatsAndSeatMapAreSaved() {
        val s = de.wanderlogplus.app.testing.Fixtures.trip()
        val d = MoveDraft("JR", "ICE 571", de.wanderlogplus.shared.model.Endpoint("Hannover"), emptyList(), de.wanderlogplus.shared.model.Endpoint("Frankfurt"))
            .copy(travelClass = "2. Klasse", seats = listOf(de.wanderlogplus.shared.model.Seat("12", "111"), de.wanderlogplus.shared.model.Seat("12", "114")), seatMap = "db-ice4-2kl")
        val (_, after) = draftChanges(s, de.wanderlogplus.shared.sync.EntityType.TRAIN, NEW_ID, d)
        val t = de.wanderlogplus.shared.model.EntityCodec.decode<de.wanderlogplus.shared.model.Train>(
            de.wanderlogplus.shared.sync.EntityRecord(de.wanderlogplus.shared.sync.EntityType.TRAIN, "x", after.mapValues { (_, v) -> de.wanderlogplus.shared.sync.FieldValue(v, "000000000000001-000000-a") }),
        )
        org.junit.Assert.assertEquals("2. Klasse", t.travelClass)
        org.junit.Assert.assertEquals(listOf("111", "114"), t.seats.map { it.seat })
        org.junit.Assert.assertEquals("db-ice4-2kl", t.seatMap)
    }
}
