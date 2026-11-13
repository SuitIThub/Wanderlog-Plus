package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.testing.Fixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** What "Als Nächstes", "Nächste Fahrt" and "Heute kompakt" show per size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NextUpWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trip = Fixtures.trip()
    private fun models(now: String, snapshot: de.wanderlogplus.client.TripSnapshot? = trip) =
        WidgetData.build(WidgetInput(snapshot, LocalDateTime.parse(now), null, null, null, null))

    private fun nextUp(m: WidgetModels, b: Bucket, check: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(b.size)
            setContext(context)
            provideComposable { NextUpContent(context, m, b) }
            check()
        }

    @Test fun tinyShowsOnlyTheTime() = nextUp(models("2027-04-02T08:00"), Bucket.TINY) {
        onNode(hasText("09:00")).assertExists()
        onNode(hasText("Alter Bergtempel")).assertDoesNotExist()
    }

    @Test fun stripShowsTimeTitleAndCountdown() = nextUp(models("2027-04-02T08:00"), Bucket.STRIP) {
        onNode(hasTestTag("nu-next")).assertExists()
        onNode(hasText("Alter Bergtempel")).assertExists()
        onNode(hasText("in 1 Std.")).assertExists()
    }

    @Test fun mediumShowsCurrentAndNextButNoLongList() = nextUp(models("2027-04-02T09:30"), Bucket.MEDIUM) {
        onNode(hasTestTag("nu-current")).assertExists()
        onNode(hasTestTag("nu-next")).assertExists()
        onNode(hasTestTag("nu-row-3")).assertDoesNotExist()
    }

    @Test fun largeListsTheRest() = nextUp(models("2027-04-02T08:00"), Bucket.LARGE) {
        onNode(hasTestTag("nu-row-0")).assertExists()
        onNode(hasTestTag("nu-row-1")).assertExists()
        onNode(hasText("Nudelküche Weizenhalm")).assertExists()
    }

    @Test fun beforeAfterAndWithoutTrip() {
        nextUp(models("2027-03-01T10:00"), Bucket.SMALL) { onNode(hasText("Tage")).assertExists() }
        nextUp(models("2027-05-01T10:00"), Bucket.SMALL) { onNode(hasText("Willkommen zurück")).assertExists() }
        nextUp(models("2027-04-02T10:00", snapshot = null), Bucket.SMALL) { onNode(hasText("Reise in der App einrichten")).assertExists() }
    }

    private fun ride(m: WidgetModels, b: Bucket, check: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(b.size)
            setContext(context)
            provideComposable { NextRideContent(context, m, b) }
            check()
        }

    @Test fun rideStripAndLarge() {
        val withTicket = trip.copy(attachments = listOf(de.wanderlogplus.shared.model.Attachment("a-1", de.wanderlogplus.shared.sync.EntityType.TRAIN, "tr-1", "h", "Ticket.pdf")))
        val m = models("2027-04-04T08:00", withTicket)
        ride(m, Bucket.STRIP) {
            onNode(hasText("10:12")).assertExists()
            onNode(hasTestTag("ride-ticket-0")).assertDoesNotExist()
        }
        ride(m, Bucket.LARGE) {
            onNode(hasText("Bahnhof Bergstadt → Bahnhof Küstenort")).assertExists()
            onNode(hasTestTag("ride-ticket-0")).assertExists()
        }
        ride(models("2027-04-02T10:00"), Bucket.MEDIUM) { onNode(hasText("Keine Fahrt in den nächsten 36 Stunden")).assertExists() }
    }

    private fun today(m: WidgetModels, b: Bucket, check: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(b.size)
            setContext(context)
            provideComposable { TodayContent(context, m, b) }
            check()
        }

    @Test fun todayMediumAndLarge() {
        val m = models("2027-04-02T08:00")
        today(m, Bucket.MEDIUM) {
            onNode(hasText("Gasthaus am Hang")).assertExists()
            onNode(hasTestTag("today-step-1")).assertDoesNotExist()
        }
        today(m, Bucket.LARGE) {
            onNode(hasTestTag("today-step-0")).assertExists()
            onNode(hasTestTag("today-step-1")).assertExists()
        }
    }
}
