package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.HourWeather
import de.wanderlogplus.shared.sync.TripWeather
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** What "Unterkunft heute", "Yen & Budget" and "Wetter" show per size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StayBudgetWeatherWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trip = Fixtures.trip().let { t ->
        t.copy(
            places = t.places + ("p-hotel-berg" to t.places.getValue("p-hotel-berg").copy(address = "1-2-3 Higashiyama, Kyoto")),
            expenses = listOf(Expense("e-1", amount = 4300.0, currency = "JPY", date = "2027-04-02", rate = 0.0061, rateDate = "2027-04-02")),
        )
    }
    private val weather = TripWeather(
        listOf(
            DayWeather("2027-04-02", place = "Bergstadt", code = 61, tempMin = 11.0, tempMax = 17.0, rainChance = 70, hours = listOf(HourWeather("09:00", 61, 12.0, 70), HourWeather("12:00", 3, 15.0, 30))),
            DayWeather("2027-04-03", code = 0, tempMax = 19.0),
        ),
    )
    private fun models(rate: StoredRate? = StoredRate(0.0061, "2027-04-02"), snapshot: de.wanderlogplus.client.TripSnapshot = trip, w: TripWeather? = weather) =
        WidgetData.build(WidgetInput(snapshot, LocalDateTime.parse("2027-04-02T10:00"), w, rate, null, null))

    private fun show(b: Bucket, content: @androidx.compose.runtime.Composable () -> Unit, check: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setAppWidgetSize(b.size)
            setContext(context)
            provideComposable { content() }
            check()
        }

    @Test fun staySizes() {
        val m = models()
        show(Bucket.SMALL, { StayContent(context, m, Bucket.SMALL) }) {
            onNode(hasText("Gasthaus am Hang")).assertExists()
            onNode(hasText("Check-out")).assertExists()
            onNode(hasTestTag("stay-address")).assertDoesNotExist()
        }
        show(Bucket.MEDIUM, { StayContent(context, m, Bucket.MEDIUM) }) { onNode(hasTestTag("stay-address")).assertExists() }
        show(Bucket.LARGE, { StayContent(context, m, Bucket.LARGE) }) {
            onNode(hasTestTag("stay-show-address")).assertExists()
            onNode(hasTestTag("stay-map")).assertExists()
            onNode(hasText("TEST-0001")).assertExists()
        }
    }

    @Test fun budgetSizes() {
        val m = models()
        show(Bucket.TINY, { BudgetContent(context, m, Bucket.TINY) }) {
            onNode(hasText("1.000 ¥")).assertExists()
            onNode(hasText("6,10 €")).assertExists()
        }
        show(Bucket.STRIP, { BudgetContent(context, m, Bucket.STRIP) }) {
            onNode(hasText("heute 4.300 ¥")).assertExists()
            onNode(hasTestTag("budget-add")).assertExists()
        }
        show(Bucket.MEDIUM, { BudgetContent(context, m, Bucket.MEDIUM) }) {
            onNode(hasTestTag("budget-table")).assertExists()
            onNode(hasText("5.000 ¥")).assertExists()
        }
        show(Bucket.STRIP, { BudgetContent(context, models(rate = null, snapshot = Fixtures.trip()), Bucket.STRIP) }) {
            onNode(hasText("Kein Kurs bekannt")).assertExists()
            onNode(hasTestTag("budget-add")).assertExists()
        }
    }

    @Test fun weatherSizes() {
        val m = models()
        show(Bucket.TINY, { WeatherContent(context, m, Bucket.TINY) }) { onNode(hasText("17°")).assertExists() }
        show(Bucket.MEDIUM, { WeatherContent(context, m, Bucket.MEDIUM) }) {
            onNode(hasTestTag("weather-today")).assertExists()
            onNode(hasTestTag("weather-tomorrow")).assertExists()
            onNode(hasTestTag("weather-hours")).assertDoesNotExist()
        }
        show(Bucket.LARGE, { WeatherContent(context, m, Bucket.LARGE) }) { onNode(hasTestTag("weather-hours")).assertExists() }
        show(Bucket.SMALL, { WeatherContent(context, models(w = null), Bucket.SMALL) }) { onNode(hasText("Noch keine Vorhersage")).assertExists() }
        val climate = TripWeather(listOf(DayWeather("2027-04-02", source = DayWeather.SOURCE_CLIMATE, tempMax = 16.0)))
        show(Bucket.SMALL, { WeatherContent(context, models(w = climate), Bucket.SMALL) }) { onNode(hasText("Klima-Mittel")).assertExists() }
    }
}
