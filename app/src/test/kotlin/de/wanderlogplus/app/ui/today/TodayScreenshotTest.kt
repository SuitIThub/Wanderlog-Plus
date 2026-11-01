package de.wanderlogplus.app.ui.today

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
import java.time.LocalDateTime

/** Records the Heute screen (fixture during/before the trip, long texts, real data when present), light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1500dp-xxhdpi")
class TodayScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun fixture(longTexts: Boolean = false): TripSnapshot {
        val base = Fixtures.trip(longTexts)
        val ticket = Attachment("at-ticket", EntityType.TRAIN, "tr-1", "a".repeat(64), "Ticket Schnellzug.pdf", "application/pdf", 10)
        return base.copy(attachments = listOf(ticket))
    }

    private fun record(name: String, snapshot: TripSnapshot, now: String, dark: Boolean) {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TodayScreen(
                        snapshot, LocalDateTime.parse(now), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {},
                        onOpenLink = {}, available = { false },
                    )
                }
            }
        }
        rule.onRoot().recordScreenshot("$name-${if (dark) "dark" else "light"}")
    }

    // Day 2: note, temple, noodle shop; temple open at 10:00.
    @Test fun duringLight() = record("today-during", fixture(), "2027-04-02T10:00", dark = false)

    @Test fun duringDark() = record("today-during", fixture(), "2027-04-02T10:00", dark = true)

    // Day 4: train with ticket and route map, change of stay, empty program.
    @Test fun travelDayLight() = record("today-travel", fixture(), "2027-04-04T08:00", dark = false)

    @Test fun travelDayDark() = record("today-travel", fixture(), "2027-04-04T08:00", dark = true)

    @Test fun beforeLight() = record("today-before", fixture(), "2027-03-10T09:00", dark = false)

    @Test fun beforeDark() = record("today-before", fixture(), "2027-03-10T09:00", dark = true)

    @Test fun longTextsLight() = record("today-long", fixture(longTexts = true), "2027-04-02T10:00", dark = false)

    @Test fun longTextsDark() = record("today-long", fixture(longTexts = true), "2027-04-02T10:00", dark = true)

    @Test fun realLight() = real(dark = false)

    @Test fun realDark() = real(dark = true)

    private fun real(dark: Boolean) {
        val snapshot = Fixtures.realOrNull()
        assumeTrue("no private real data", snapshot != null)
        record("today-real", snapshot!!, "2027-05-20T10:00", dark)
    }
}
