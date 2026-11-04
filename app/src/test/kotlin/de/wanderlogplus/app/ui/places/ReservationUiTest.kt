package de.wanderlogplus.app.ui.places

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ReservationUiTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun snapshot(reserved: Boolean = false): TripSnapshot {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "pg", Place("pg", name = "Moosgarten"))
        seed(EntityType.SAVED_PLACE, "sg", SavedPlace("sg", "pg", "r1", dossier = PlaceDossier(hints = listOf("Nur mit Reservierung per Postkarte")), reserved = reserved))
        seed(EntityType.DAY, "d1", Day("d1", "2027-05-09"))
        seed(EntityType.DAY_ITEM, "i1", DayItem("i1", "d1", "a0", DayItemKind.PLACE, "pg"))
        return TripSnapshot.from(store.all())
    }

    @Test fun placeShowsTheReasonAndTheReservedSwitch() {
        val s = snapshot()
        val calls = mutableListOf<Pair<String, Boolean>>()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(s, "sg", onBack = null, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, onSetReserved = { id, r -> calls += id to r })
            }
        }
        rule.onNode(hasText("Reservierung nötig") and hasAnyAncestor(hasTestTag("place-reservation")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Nur mit Reservierung per Postkarte") and hasAnyAncestor(hasTestTag("place-reservation")), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("place-reserved-toggle").assertIsOff().performClick()
        assertEquals(listOf("sg" to true), calls)
    }

    @Test fun todayBeforeTheTripListsOpenReservationsAndOpensThePlace() {
        val s = snapshot()
        var opened: String? = null
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(s, LocalDateTime.parse("2027-05-01T12:00"), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {}, onOpenPlace = { opened = it })
            }
        }
        rule.onNode(hasText("Eingeplant So, 09.05. · Nur mit Reservierung per Postkarte") and hasAnyAncestor(hasTestTag("reservation-0")), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("reservation-0").performClick()
        assertEquals("sg", opened)
    }

    @Test fun reservedPlacesAreNotListed() {
        val s = snapshot(reserved = true)
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(s, LocalDateTime.parse("2027-05-01T12:00"), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {})
            }
        }
        rule.onNode(hasTestTag("reservation-0")).assertDoesNotExist()
    }
}
