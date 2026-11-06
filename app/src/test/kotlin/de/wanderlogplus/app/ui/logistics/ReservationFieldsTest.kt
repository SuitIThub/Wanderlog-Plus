package de.wanderlogplus.app.ui.logistics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.ReservationWindow
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ReservationFieldsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun toWindowAndBack() {
        assertNull(ReservationDraft().toWindow(null))
        assertEquals(ReservationWindow("2027-04-01", "2027-04-20", byHand = true), ReservationDraft("2027-04-01", "2027-04-20").toWindow(null))
        assertEquals(ReservationWindow(done = true, byHand = true), ReservationDraft(done = true).toWindow(null))
        assertEquals(ReservationDraft("2027-04-01", "", true), ReservationWindow(opens = "2027-04-01", done = true).toDraft())
        assertEquals(ReservationDraft(), (null as ReservationWindow?).toDraft())
    }

    @Test
    fun validateOrder() {
        assertNull(validateReservation(ReservationDraft("2027-04-01", "2027-04-20")))
        assertEquals("Reservierbar ab bitte als JJJJ-MM-TT", validateReservation(ReservationDraft("1.4.", "")))
        assertEquals("Reservieren bis bitte als JJJJ-MM-TT", validateReservation(ReservationDraft("", "morgen")))
        assertEquals("„Reservierbar ab“ liegt nach „Reservieren bis“", validateReservation(ReservationDraft("2027-04-21", "2027-04-20")))
    }

    @Test
    fun textOfAWindow() {
        assertEquals("Reservierbar ab Do, 01.04. · Reservieren bis Di, 20.04.", reservationText(ReservationWindow("2027-04-01", "2027-04-20"), estimated = false))
        assertEquals("Reservieren bis Di, 20.04. (geschätzt)", reservationText(ReservationWindow(by = "2027-04-20"), estimated = true))
        assertEquals("Reservieren bis Di, 20.04. · erledigt", reservationText(ReservationWindow(by = "2027-04-20", done = true), estimated = false))
    }

    @Test
    fun trainAndStayDraftsSaveTheWindow() {
        val snap = Fixtures.trip()
        val d = draftOf(snap, EntityType.TRAIN, "tr-1")!!.copy(reservation = ReservationDraft("2027-03-04", "2027-03-30"))
        val after = draftChanges(snap, EntityType.TRAIN, "tr-1", d).second
        val train = EntityCodec.decode<Train>(EntityRecord(EntityType.TRAIN, "tr-1", after.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) }))
        assertEquals(ReservationWindow("2027-03-04", "2027-03-30", byHand = true), train.reservation)
        assertEquals(ReservationDraft("2027-03-04", "2027-03-30"), draftOf(TripSnapshotWith(snap, train), EntityType.TRAIN, "tr-1")!!.reservation)

        val stay = snap.stays.first()
        val sd = stayDraftOf(snap, stay.id)!!.copy(reservation = ReservationDraft(by = "2027-03-15", done = true))
        assertEquals(ReservationWindow(by = "2027-03-15", done = true, byHand = true), stayToSave(snap, sd, stay, "new").stay.reservation)
        assertEquals("Reservieren bis bitte als JJJJ-MM-TT", validateStay(sd.copy(reservation = ReservationDraft(by = "x"))))
    }

    @Test
    fun fieldsEditTheDraft() {
        var d by mutableStateOf(ReservationDraft())
        rule.setContent { WanderlogPlusTheme { ReservationFields(d, showDone = true, onChange = { d = it }) } }
        rule.onNodeWithTag("res-opens").performTextInput("2027-04-01")
        rule.onNodeWithTag("res-by").performTextInput("2027-04-20")
        rule.onNodeWithTag("res-done").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(ReservationDraft("2027-04-01", "2027-04-20", true), d)
    }

    private fun withWindows(): de.wanderlogplus.client.TripSnapshot {
        val base = Fixtures.trip()
        return base.copy(
            trains = base.trains.map { it.copy(reservation = ReservationWindow(opens = "2027-03-04", by = "2027-03-30")) },
            savedPlaces = base.savedPlaces.map { if (it.id == "s-garten") it.copy(reservationWindow = ReservationWindow(by = "2027-03-20", byHand = true)) else it },
        )
    }

    @Test
    fun rideDetailShowsTheWindow() {
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(withWindows(), EntityType.TRAIN, "tr-1", onBack = {}) } }
        rule.onNodeWithText("Reservierbar ab Do, 04.03. · Reservieren bis Di, 30.03.").assertExists()
    }

    @Test
    fun placeShowsAndEditsTheWindow() {
        var saved: Pair<String, ReservationWindow?>? = null
        rule.setContent {
            WanderlogPlusTheme {
                de.wanderlogplus.app.ui.places.PlaceDetailScreen(
                    withWindows(), "s-garten", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onSetReservationWindow = { id, w -> saved = id to w },
                )
            }
        }
        rule.onNodeWithText("Reservieren bis Sa, 20.03.").assertExists()
        rule.onNodeWithTag("place-reservation-edit").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("res-opens").performTextInput("2027-03-01")
        rule.onNodeWithTag("place-reservation-save").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("s-garten" to ReservationWindow(opens = "2027-03-01", by = "2027-03-20", byHand = true), saved)
    }

    private fun TripSnapshotWith(s: de.wanderlogplus.client.TripSnapshot, t: Train) = s.copy(trains = s.trains.map { if (it.id == t.id) t else it })
}
