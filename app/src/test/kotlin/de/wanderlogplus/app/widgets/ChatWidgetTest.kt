package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/** The chat widget: buttons per size and the "Was jetzt?" result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = LocalDateTime.parse("2027-04-02T14:30")
    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val cards = listOf(
        QuickCard("Nudelküche Weizenhalm", "Kurze Wartezeit", "s-nudel", "img-nudel", 650),
        QuickCard("Teehaus", "Matcha", null, null, 1240),
        QuickCard("Markt", null, null, null, null),
        QuickCard("Bäckerei", null, null, null, 300),
    )
    private fun models(quick: QuickResult? = null, title: String? = null) =
        WidgetData.build(WidgetInput(Fixtures.trip(), now, null, null, title, quick))
    private fun done(minutesAgo: Long = 25) = QuickResult("food", "c-9", QuickResult.DONE, ms(now.minusMinutes(minutesAgo)), "2027-04-02", cards)

    private fun show(m: WidgetModels, b: Bucket, check: GlanceAppWidgetUnitTest.() -> Unit) = runGlanceAppWidgetUnitTest {
        setAppWidgetSize(b.size)
        setContext(context)
        provideComposable { ChatContent(context, m, b, image = null) }
        check()
    }

    @Test fun tinyIsOnlyTheChat() = show(models(), Bucket.TINY) {
        onNode(hasTestTag("chat-open")).assertExists()
        onNode(hasTestTag("ask-sight")).assertDoesNotExist()
    }

    @Test fun stripHasTheButtonsButNoResult() = show(models(done()), Bucket.STRIP) {
        onNode(hasTestTag("ask-sight")).assertExists()
        onNode(hasTestTag("ask-food")).assertExists()
        onNode(hasTestTag("quick-top")).assertDoesNotExist()
    }

    @Test fun mediumShowsRunningThenTheTopPlace() {
        show(models(QuickResult("food", null, QuickResult.RUNNING, ms(now), "2027-04-02")), Bucket.MEDIUM) {
            onNode(hasTestTag("quick-running")).assertExists()
        }
        show(models(done()), Bucket.MEDIUM) {
            onNode(hasTestTag("quick-top")).assertExists()
            onNode(hasText("Nudelküche Weizenhalm")).assertExists()
            onNode(hasText("650 m")).assertExists()
            onNode(hasTestTag("quick-more-0")).assertDoesNotExist()
            onNode(hasText("von 14:05")).assertExists()
        }
    }

    @Test fun largeListsMoreSuggestions() = show(models(done()), Bucket.LARGE) {
        onNode(hasTestTag("quick-more-0")).assertExists()
        onNode(hasTestTag("quick-more-2")).assertExists()
        onNode(hasText("1,2 km")).assertExists()
    }

    @Test fun failedStaleAndEmpty() {
        show(models(QuickResult("sight", "c-1", QuickResult.FAILED, ms(now), "2027-04-02")), Bucket.MEDIUM) {
            onNode(hasText("Keine Antwort – im Chat erneut versuchen")).assertExists()
        }
        show(models(done(minutesAgo = 150)), Bucket.MEDIUM) { onNode(hasText("älter")).assertExists() }
        show(models(title = "Ramen in Kyoto"), Bucket.WIDE) {
            onNode(hasText("Frag den Reise-Chat")).assertExists()
            onNode(hasText("Ramen in Kyoto")).assertExists()
        }
    }

    @Test fun distances() {
        assertEquals("650 m", distanceText(648))
        assertEquals("1,2 km", distanceText(1240))
        assertEquals("40 m", distanceText(37))
    }
}
