package de.wanderlogplus.app.ui.today

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class MinuteNowTest {
    @get:Rule val rule = createComposeRule()

    private fun snapshot(): TripSnapshot {
        val store = InMemoryEntityStore()
        val trip = Trip("t1", "Japan", "2027-05-08", "2027-05-12")
        var rec = EntityRecord(EntityType.TRIP, "t1")
        (EntityCodec.fields(trip) + ("deleted" to JsonPrimitive(false))).forEach { (k, v) ->
            rec = rec.apply(FieldChange(EntityType.TRIP, "t1", k, v, Hlc(1, 0, "importer").encode()))!!
        }
        store.put(rec)
        return TripSnapshot.from(store.all())
    }

    private var clockNow = LocalDateTime.parse("2027-05-07T23:59:30")

    private fun show(owner: LifecycleOwner? = null) {
        val snap = snapshot()
        rule.setContent {
            val content = @androidx.compose.runtime.Composable {
                WanderlogPlusTheme {
                    TodayScreen(snap, rememberMinuteNow { clockNow }, onOpenDay = {}, onOpenTrip = {}, onSyncNow = {})
                }
            }
            if (owner == null) content() else CompositionLocalProvider(LocalLifecycleOwner provides owner) { content() }
        }
    }

    @Test fun minuteTickMovesFromBeforeToDuring() {
        show()
        rule.onNodeWithText("1 Tag").assertIsDisplayed()
        clockNow = LocalDateTime.parse("2027-05-08T00:00:05")
        rule.mainClock.advanceTimeBy(60_000)
        rule.waitForIdle()
        rule.onNodeWithText("1 Tag").assertDoesNotExist()
        rule.onNodeWithText("Kein Programm", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Tagesablauf", ignoreCase = true).assertIsDisplayed()
    }

    @Test fun resumeRefreshesNow() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        show(owner)
        rule.onNodeWithText("1 Tag").assertIsDisplayed()
        clockNow = LocalDateTime.parse("2027-05-08T08:00:00")
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitForIdle()
        rule.onNodeWithText("1 Tag").assertDoesNotExist()
        rule.onNodeWithText("Tagesablauf", ignoreCase = true).assertIsDisplayed()
    }
}
