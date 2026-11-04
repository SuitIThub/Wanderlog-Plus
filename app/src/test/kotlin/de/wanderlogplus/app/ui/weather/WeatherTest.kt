package de.wanderlogplus.app.ui.weather

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.design.LocalOpenChat
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.app.ui.trip.TripScreen
import de.wanderlogplus.app.weather.WeatherRepository
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class WeatherTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun snapshot(): TripSnapshot {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Sakura", lat = 35.0, lng = 135.77))
        seed(EntityType.STAY, "s0", Stay("s0", "hotel", "r1", "2027-05-08", "2027-05-12"))
        seed(EntityType.DAY, "d1", Day("d1", "2027-05-09", "Tempeltag"))
        seed(EntityType.PLACE, "pg", Place("pg", name = "Moosgarten"))
        seed(EntityType.PLACE, "pm", Place("pm", name = "Museum Gamma"))
        seed(EntityType.DAY_ITEM, "i1", DayItem("i1", "d1", "a0", DayItemKind.PLACE, "pg", time = "10:00"))
        seed(EntityType.DAY_ITEM, "i2", DayItem("i2", "d1", "a1", DayItemKind.PLACE, "pm"))
        return TripSnapshot.from(store.all())
    }

    private val weather = TripWeather(
        listOf(
            DayWeather(
                "2027-05-09", "Hotel Sakura", code = 63, tempMin = 14.0, tempMax = 19.0, rainChance = 85, rainMm = 12.0,
                hours = listOf(HourWeather("09:00", 63, 15.0, 90), HourWeather("12:00", 61, 17.0, 70)),
            ),
            DayWeather("2027-05-10", "Hotel Sakura", code = 1, tempMin = 15.0, tempMax = 24.0, rainChance = 10, rainMm = 0.0),
            DayWeather("2027-05-11", "Hotel Sakura", source = DayWeather.SOURCE_CLIMATE, tempMin = 12.0, tempMax = 21.0, rainyDays = 40),
        ),
        fetchedAt = 1,
    )

    @Test fun todayShowsTheWeatherWithARainHintForOutdoorPlaces() {
        val s = snapshot()
        var chats = 0
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalTripWeather provides weather, LocalOpenChat provides { chats++ }) {
                    TodayScreen(s, LocalDateTime.parse("2027-05-09T08:00"), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {})
                }
            }
        }
        rule.onNode(hasText("Heute · Regen · 14–19 °C") and hasAnyAncestor(hasTestTag("today-weather"))).assertIsDisplayed()
        rule.onNode(hasText("Regen 85 % (12 mm) · Vorhersage JMA") and hasAnyAncestor(hasTestTag("today-weather"))).assertExists()
        rule.onNode(hasText("Regen erwartet – Moosgarten ist eher etwas für gutes Wetter.") and hasAnyAncestor(hasTestTag("day-rain-hint"))).assertExists()
        rule.onNode(hasText("Morgen · Überwiegend klar · 15–24 °C") and hasAnyAncestor(hasTestTag("tomorrow-weather"))).assertExists()
        rule.onNodeWithTag("day-rain-replan").performClick()
        assertEquals(1, chats)

        // The hours unfold on tap.
        rule.onNode(hasTestTag("day-weather-hours")).assertDoesNotExist()
        rule.onNode(hasTestTag("day-weather-toggle") and hasAnyAncestor(hasTestTag("today-weather")), useUnmergedTree = true).performClick()
        rule.onNode(hasText("90 %") and hasAnyAncestor(hasTestTag("day-weather-hours")), useUnmergedTree = true).assertExists()
    }

    @Test fun tripDaysShowIconAndTemperatureAndClimateWithAverage() {
        val s = snapshot()
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalTripWeather provides weather) { TripScreen(s, onOpenDay = {}, today = "2027-05-09") }
            }
        }
        rule.onNode(hasText("19°") and hasAnyAncestor(hasTestTag("weather-badge-2027-05-09")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Ø 21°") and hasAnyAncestor(hasTestTag("weather-badge-2027-05-11")), useUnmergedTree = true).assertExists()
    }

    @Test fun repositoryKeepsTheWeatherOfflineAndAsksOnlyWhenOld() = runBlocking {
        val body = Json.encodeToString(TripWeather.serializer(), weather.copy(fetchedAt = 1_000))
        var calls = 0
        var down = false
        var now = 1_000L
        val file = tmp.newFile("weather.json")
        val api = { SyncApi(HttpClient(MockEngine { calls++; if (down) respond("", HttpStatusCode.ServiceUnavailable) else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }), "https://s", "tok") }
        val repo = WeatherRepository(api = { api() }, file = file, now = { now })
        repo.refresh()
        assertEquals(1, calls)
        assertEquals(63, repo.weather.value?.forDate("2027-05-09")?.code)
        now += 60 * 60_000L
        repo.refresh()
        assertEquals(1, calls) // still fresh

        // A new start reads the file; old and the server down: the stored weather stays.
        now += WeatherRepository.MAX_AGE_MILLIS
        down = true
        val again = WeatherRepository(api = { api() }, file = file, now = { now })
        again.refresh()
        assertEquals(2, calls)
        assertEquals(63, again.weather.value?.forDate("2027-05-09")?.code)
    }
}
