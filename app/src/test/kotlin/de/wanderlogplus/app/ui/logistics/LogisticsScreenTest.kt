package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class LogisticsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun snapshot(): TripSnapshot {
        seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Kyoto"))
        seed(EntityType.TRAIN, "t1", Train("t1", "JR", Endpoint("Kyoto", date = "2027-05-12", time = "09:00"), Endpoint("Tokio", date = "2027-05-12", time = "11:30"), "TRAIN99"))
        seed(EntityType.STAY, "s1", Stay("s1", "hotel", checkIn = "2027-05-09", checkOut = "2027-05-12", confirmationNumber = "STAY42", cancellationDeadline = "2027-05-01"))
        seed(
            EntityType.FLIGHT, "f1",
            Flight(
                "f1", "ANA", "NH", "NH 203", Endpoint("München", "MUC", date = "2027-05-08", time = "10:00"),
                Endpoint("Tokio", "HND", date = "2027-05-08", time = "20:00"), "FLT123", listOf("Anna Beispiel"), note = "**Sitz** 12A",
            ),
        )
        return TripSnapshot.from(store.all())
    }

    @Test fun entriesAreChronologicalAndShowConfirmationNumbers() {
        val snap = snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap) } }
        val f = rule.onNodeWithTag("logistics-f1").getBoundsInRoot().top
        val s = rule.onNodeWithTag("logistics-s1").getBoundsInRoot().top
        val t = rule.onNodeWithTag("logistics-t1").getBoundsInRoot().top
        assertTrue(f < s && s < t)
        rule.onNodeWithText("FLT123", substring = true).assertIsDisplayed()
        rule.onNodeWithText("STAY42", substring = true).assertIsDisplayed()
        rule.onNodeWithText("TRAIN99", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Hotel Kyoto").assertIsDisplayed()
        assertEquals(3, rule.onAllNodesWithText("Live-Status: –").fetchSemanticsNodes().size)
    }

    @Test fun sortKeyPutsMissingTimeAfterTimedAndMissingDateLast() {
        val untimed = Flight("a", depart = Endpoint(date = "2027-05-08"))
        val timed = Flight("b", depart = Endpoint(date = "2027-05-08", time = "23:00"))
        val undated = Train("c")
        val snap = TripSnapshot.from(emptyList()).copy(flights = listOf(untimed, timed), trains = listOf(undated))
        assertEquals(listOf("b", "a", "c"), logisticsEntries(snap).map { it.id })
    }
}
