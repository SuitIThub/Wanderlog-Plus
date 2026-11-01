package de.wanderlogplus.app.ui.logistics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Store-backed booking fixture shared by the list and detail tests (confirmation "ABC123", traveler "Max Muster"). */
internal object LogisticsFixture {
    private inline fun <reified T> InMemoryEntityStore.seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        put(rec)
    }

    fun snapshot(withAttachment: Boolean = false): TripSnapshot {
        val store = InMemoryEntityStore()
        store.seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Kyoto", lat = 35.0, lng = 135.7))
        store.seed(EntityType.PLACE, "fra", Place("fra", name = "Frankfurt", lat = 50.03, lng = 8.56))
        store.seed(EntityType.PLACE, "hnd", Place("hnd", name = "Tokio Haneda", lat = 35.55, lng = 139.78))
        store.seed(
            EntityType.TRAIN, "t1",
            Train(
                "t1", "JR Nara Line", Endpoint("Kyoto", date = "2027-05-12", time = "09:00"),
                Endpoint("Tokio", date = "2027-05-12", time = "11:30"), "TRAIN99", listOf("Max Muster"),
            ),
        )
        store.seed(
            EntityType.STAY, "s1",
            Stay(
                "s1", "hotel", checkIn = "2027-05-09", checkOut = "2027-05-12", confirmationNumber = "STAY42",
                travelerNames = listOf("Max Muster"), cancellationDeadline = "2027-05-01", cancellationPolicy = "Kostenlos bis 1. Mai",
            ),
        )
        store.seed(
            EntityType.FLIGHT, "f1",
            Flight(
                "f1", "Lufthansa", "LH", "716", Endpoint("Frankfurt", "FRA", "fra", date = "2027-05-08", time = "10:00"),
                Endpoint("Tokio Haneda", "HND", "hnd", date = "2027-05-08", time = "20:00"), "ABC123", listOf("Max Muster"), note = "**Sitz** 12A",
            ),
        )
        if (withAttachment) {
            store.seed(EntityType.ATTACHMENT, "a1", Attachment("a1", EntityType.STAY, "s1", "hash1", "Buchung.pdf", "application/pdf", 2048))
            store.seed(EntityType.ATTACHMENT, "a2", Attachment("a2", EntityType.FLIGHT, "f1", "hash2", "Ticket.pdf", "application/pdf", 1024))
        }
        return TripSnapshot.from(store.all())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class LogisticsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tabsSeparateMovesAndStays() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap) } }
        rule.onNodeWithTag("logistics-f1").assertIsDisplayed()
        rule.onNodeWithTag("logistics-t1").assertIsDisplayed()
        rule.onNodeWithTag("logistics-s1").assertDoesNotExist()
        rule.onNodeWithText("Flug LH 716").assertIsDisplayed()
        rule.onNodeWithText("Zug JR Nara Line").assertIsDisplayed()
        rule.onNodeWithText("Frankfurt → Tokio Haneda").assertIsDisplayed()

        rule.onNodeWithTag("tab-stays").performClick()
        rule.onNodeWithTag("logistics-s1").assertIsDisplayed()
        rule.onNodeWithText("Hotel Kyoto").assertIsDisplayed()
        rule.onNodeWithTag("logistics-f1").assertDoesNotExist()

        rule.onNodeWithTag("tab-moves").performClick()
        rule.onNodeWithTag("logistics-f1").assertIsDisplayed()
    }

    @Test fun listHidesConfirmationAndTravelers() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap) } }
        listOf("ABC123", "TRAIN99", "Max Muster").forEach {
            assertEquals(it, 0, rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size)
        }
        rule.onNodeWithTag("tab-stays").performClick()
        listOf("STAY42", "Max Muster", "Kostenlos").forEach {
            assertEquals(it, 0, rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().size)
        }
    }

    @Test fun groupedByDate() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap) } }
        val h1 = rule.onNodeWithText("Sa 08.05.").getBoundsInRoot().top
        val f = rule.onNodeWithTag("logistics-f1").getBoundsInRoot().top
        val h2 = rule.onNodeWithText("Mi 12.05.").getBoundsInRoot().top
        val t = rule.onNodeWithTag("logistics-t1").getBoundsInRoot().top
        assertTrue(h1 < f && f < h2 && h2 < t)
    }

    @Test fun staysShowNightsAndFutureDeadlineOnly() {
        val snap = LogisticsFixture.snapshot()
        var today by mutableStateOf("2027-04-20")
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap, today = today) } }
        rule.onNodeWithTag("tab-stays").performClick()
        rule.onNodeWithText("3 Nächte · 09.–12.05.").assertIsDisplayed()
        rule.onNodeWithText("Storno bis 01.05.").assertIsDisplayed()
        today = "2027-05-02"
        rule.onNodeWithText("Storno bis 01.05.").assertDoesNotExist()
    }

    @Test fun movesShowAttachmentCountAndOpenDetail() {
        val snap = LogisticsFixture.snapshot(withAttachment = true)
        val opened = mutableListOf<LogisticsEntry>()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap, onOpen = { opened += it }) } }
        rule.onNodeWithText("📎 1").assertIsDisplayed()
        rule.onNodeWithTag("logistics-f1").performClick()
        rule.onNodeWithTag("tab-stays").performClick()
        rule.onNodeWithTag("logistics-s1").performClick()
        assertEquals(listOf("f1", "s1"), opened.map { it.id })
    }

    @Test fun emptyTabsShowHints() {
        val snap = TripSnapshot.from(emptyList())
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap) } }
        rule.onNodeWithText("Keine Fahrten.").assertIsDisplayed()
        rule.onNodeWithTag("tab-stays").performClick()
        rule.onNodeWithText("Keine Unterkünfte.").assertIsDisplayed()
    }

    /** Former `entriesAreChronologicalAndShowConfirmationNumbers`: order stays; booking data moved to the detail page. */
    @Test fun movesAreChronological() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(snap) } }
        val f = rule.onNodeWithTag("logistics-f1").getBoundsInRoot().top
        val t = rule.onNodeWithTag("logistics-t1").getBoundsInRoot().top
        assertTrue(f < t)
    }

    /** The stay link buttons moved to the detail page ("Navigation"). */
    @Test fun stayShowsNavigateAndTransitOnly() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snap, EntityType.STAY, "s1", onBack = {}, onOpenLink = {}) } }
        rule.onNodeWithTag("link-NAVIGATE").assertExists()
        rule.onNodeWithTag("link-TRANSIT").assertExists()
        rule.onNodeWithTag("link-TABELOG").assertDoesNotExist()
    }

    @Test fun sortKeyPutsMissingTimeAfterTimedAndMissingDateLast() {
        val untimed = Flight("a", depart = Endpoint(date = "2027-05-08"))
        val timed = Flight("b", depart = Endpoint(date = "2027-05-08", time = "23:00"))
        val undated = Train("c")
        val snap = TripSnapshot.from(emptyList()).copy(flights = listOf(untimed, timed), trains = listOf(undated))
        assertEquals(listOf("b", "a", "c"), logisticsEntries(snap).map { it.id })
    }

    @Test fun flightCardTitlePrefersCodeAndAvoidsDuplicates() {
        assertEquals("Flug LH 716", flightCardTitle(Flight("x", "Lufthansa", "LH", "716")))
        assertEquals("Flug NH 203", flightCardTitle(Flight("x", "ANA", "NH", "NH 203")))
        assertEquals("Flug Testair", flightCardTitle(Flight("x", airline = "Testair")))
        assertEquals("Flug", flightCardTitle(Flight("x")))
    }

    @Test fun stayPeriodFormats() {
        assertEquals("3 Nächte · 09.–12.05.", stayPeriod(Stay("s", checkIn = "2027-05-09", checkOut = "2027-05-12")))
        assertEquals("1 Nacht · 30.04.–01.05.", stayPeriod(Stay("s", checkIn = "2027-04-30", checkOut = "2027-05-01")))
        assertEquals("ab 09.05.", stayPeriod(Stay("s", checkIn = "2027-05-09")))
        assertEquals(null, stayPeriod(Stay("s")))
    }
}
