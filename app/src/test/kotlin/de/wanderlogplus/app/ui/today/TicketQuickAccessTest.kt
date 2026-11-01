package de.wanderlogplus.app.ui.today

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
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
class TicketQuickAccessTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    @Test fun tappingTicketOfTodaysTrainOpensIt() {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-18", "2027-05-22"))
        seed(
            EntityType.TRAIN, "tr1",
            Train("tr1", "JR", depart = Endpoint("Kyoto", date = "2027-05-20", time = "09:00"), arrive = Endpoint("Osaka")),
        )
        seed(EntityType.ATTACHMENT, "at1", Attachment("at1", EntityType.TRAIN, "tr1", "f".repeat(64), "Ticket.pdf", "application/pdf", 10))
        seed(EntityType.ATTACHMENT, "at2", Attachment("at2", EntityType.TRAIN, "other", "e".repeat(64), "Anderes.pdf", "application/pdf", 10))
        var opened: Attachment? = null
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(
                    TripSnapshot.from(store.all()), LocalDateTime.parse("2027-05-20T08:00"),
                    onOpenDay = {}, onOpenTrip = {}, onSyncNow = {},
                    onOpenAttachment = { opened = it },
                )
            }
        }
        rule.onNodeWithTag("ticket-at2").assertDoesNotExist()
        rule.onNodeWithTag("ticket-at1").performClick()
        assertEquals("at1", opened?.id)
        assertEquals("Ticket.pdf", opened?.fileName)
    }
}
