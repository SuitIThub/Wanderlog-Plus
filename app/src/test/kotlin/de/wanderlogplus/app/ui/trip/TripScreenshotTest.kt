package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
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

/** Records the trip list and the three day-detail tabs (fixture, long texts, real data when present), light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1500dp-xxhdpi")
class TripScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun fixture(longTexts: Boolean = false): TripSnapshot {
        val base = Fixtures.trip(longTexts)
        val dayFile = Attachment("at-day", EntityType.DAY, "d-2", "b".repeat(64), "Tempelplan.pdf", "application/pdf", 245_000)
        return base.copy(attachments = listOf(dayFile))
    }

    private fun suffix(dark: Boolean) = if (dark) "dark" else "light"

    private fun list(name: String, snapshot: TripSnapshot, today: String?, dark: Boolean) {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TripScreen(snapshot, onOpenDay = {}, today = today)
                }
            }
        }
        rule.onRoot().recordScreenshot("$name-${suffix(dark)}")
    }

    /** One screenshot per tab of the day [date]. */
    private fun day(name: String, snapshot: TripSnapshot, date: String, dark: Boolean) {
        val noAttachments = de.wanderlogplus.app.ui.attachments.AttachmentActions(
            available = { false }, onAdd = { _, _ -> }, onAddToDay = {}, onOpen = {}, onDelete = {},
        )
        rule.setContent {
            WanderlogPlusTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DayDetailScreen(
                        snapshot, date, onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {},
                        attachments = noAttachments,
                    )
                }
            }
        }
        rule.onRoot().recordScreenshot("$name-program-${suffix(dark)}")
        rule.onNodeWithTag("tab-suggestions").performClick()
        rule.onRoot().recordScreenshot("$name-suggestions-${suffix(dark)}")
        rule.onNodeWithTag("tab-notes").performClick()
        rule.onRoot().recordScreenshot("$name-notes-${suffix(dark)}")
    }

    @Test fun listLight() = list("trip-list", fixture(), "2027-04-02", dark = false)

    @Test fun listDark() = list("trip-list", fixture(), "2027-04-02", dark = true)

    @Test fun listLongLight() = list("trip-list-long", fixture(longTexts = true), null, dark = false)

    @Test fun listLongDark() = list("trip-list-long", fixture(longTexts = true), null, dark = true)

    // Day 2: note, temple, noodle shop, a day attachment.
    @Test fun dayLight() = day("trip-day", fixture(), "2027-04-02", dark = false)

    @Test fun dayDark() = day("trip-day", fixture(), "2027-04-02", dark = true)

    // Day 4: train, change of stay, empty program.
    @Test fun travelDayLight() = day("trip-travelday", fixture(), "2027-04-04", dark = false)

    @Test fun travelDayDark() = day("trip-travelday", fixture(), "2027-04-04", dark = true)

    @Test fun dayLongLight() = day("trip-day-long", fixture(longTexts = true), "2027-04-02", dark = false)

    @Test fun dayLongDark() = day("trip-day-long", fixture(longTexts = true), "2027-04-02", dark = true)

    @Test fun realListLight() = real { list("trip-list-real", it, "2027-05-20", dark = false) }

    @Test fun realListDark() = real { list("trip-list-real", it, "2027-05-20", dark = true) }

    @Test fun realDayLight() = real { day("trip-day-real", it, "2027-05-20", dark = false) }

    @Test fun realDayDark() = real { day("trip-day-real", it, "2027-05-20", dark = true) }

    private fun real(block: (TripSnapshot) -> Unit) {
        val snapshot = Fixtures.realOrNull()
        assumeTrue("no private real data", snapshot != null)
        block(snapshot!!)
    }
}
