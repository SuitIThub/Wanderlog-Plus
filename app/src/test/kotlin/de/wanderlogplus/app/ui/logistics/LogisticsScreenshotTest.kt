package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records Logistik (both tabs) and booking details: fixture, long texts, real data when present. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1500dp-xxhdpi")
class LogisticsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    /** Before the fixture's cancellation deadline (2027-03-20), so the badge shows. */
    private val today = "2027-03-01"

    private fun suffix(dark: Boolean) = if (dark) "dark" else "light"

    private fun withTicket(s: TripSnapshot) = s.copy(
        attachments = s.attachments + Attachment("a-ticket", EntityType.FLIGHT, "fl-1", "h1", "Bordkarte.pdf", "application/pdf", 120_000) +
            Attachment("a-hotel", EntityType.STAY, "st-berg", "h2", "Buchung.pdf", "application/pdf", 80_000),
    )

    private fun shot(name: String, dark: Boolean, before: () -> Unit = {}, content: @Composable () -> Unit) {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        before()
        rule.onRoot().recordScreenshot("$name-${suffix(dark)}")
    }

    private fun moves(name: String, s: TripSnapshot, dark: Boolean) = shot(name, dark) { LogisticsScreen(s, today = today) }

    private fun stays(name: String, s: TripSnapshot, dark: Boolean) =
        shot(name, dark, before = { rule.onNodeWithTag("tab-stays").performClick() }) { LogisticsScreen(s, today = today) }

    private fun detail(name: String, s: TripSnapshot, type: EntityType, id: String, dark: Boolean) {
        val actions = AttachmentActions(available = { it != "h2" }, onAdd = { _, _ -> }, onAddToDay = {}, onOpen = {}, onDelete = {})
        shot(name, dark) { LogisticsDetailScreen(s, type, id, onBack = {}, attachments = actions, onOpenLink = {}, today = today) }
    }

    @Test fun movesLight() = moves("logistics-moves", withTicket(Fixtures.trip()), dark = false)
    @Test fun movesDark() = moves("logistics-moves", withTicket(Fixtures.trip()), dark = true)
    @Test fun movesLongLight() = moves("logistics-moves-long", Fixtures.trip(longTexts = true), dark = false)

    @Test fun staysLight() = stays("logistics-stays", withTicket(Fixtures.trip()), dark = false)
    @Test fun staysDark() = stays("logistics-stays", withTicket(Fixtures.trip()), dark = true)
    @Test fun staysLongLight() = stays("logistics-stays-long", Fixtures.trip(longTexts = true), dark = false)

    @Test fun flightDetailLight() = detail("logistics-detail-flight", withTicket(Fixtures.trip()), EntityType.FLIGHT, "fl-1", dark = false)
    @Test fun flightDetailDark() = detail("logistics-detail-flight", withTicket(Fixtures.trip()), EntityType.FLIGHT, "fl-1", dark = true)
    @Test fun stayDetailLight() = detail("logistics-detail-stay", withTicket(Fixtures.trip()), EntityType.STAY, "st-berg", dark = false)
    @Test fun stayDetailDark() = detail("logistics-detail-stay", withTicket(Fixtures.trip()), EntityType.STAY, "st-berg", dark = true)
    @Test fun stayDetailLongLight() =
        detail("logistics-detail-stay-long", Fixtures.trip(longTexts = true), EntityType.STAY, "st-berg", dark = false)

    @Test fun realMovesLight() = real { moves("logistics-moves-real", it, dark = false) }
    @Test fun realMovesDark() = real { moves("logistics-moves-real", it, dark = true) }
    @Test fun realStaysLight() = real { stays("logistics-stays-real", it, dark = false) }
    @Test fun realStaysDark() = real { stays("logistics-stays-real", it, dark = true) }
    @Test fun realFlightDetailLight() = real { s ->
        assumeTrue("no flight", s.flights.isNotEmpty())
        detail("logistics-detail-flight-real", s, EntityType.FLIGHT, s.flights.first().id, dark = false)
    }
    @Test fun realStayDetailDark() = real { s ->
        assumeTrue("no stay", s.stays.isNotEmpty())
        detail("logistics-detail-stay-real", s, EntityType.STAY, s.stays.first().id, dark = true)
    }

    private fun real(block: (TripSnapshot) -> Unit) {
        val snapshot = Fixtures.realOrNull()
        assumeTrue("no private real data", snapshot != null)
        block(snapshot!!)
    }
}
