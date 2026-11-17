package de.wanderlogplus.app.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.movementsOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onRoot
import de.wanderlogplus.app.testing.recordScreenshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class DayTimelineTest {
    @get:Rule val rule = createComposeRule()

    private fun show(snapshot: TripSnapshot, date: String, moveTagPrefix: String = "movement-") {
        val view = snapshot.queries()!!.days().first { it.date == date }
        rule.setContent {
            WanderlogPlusTheme {
                DayTimeline(
                    snapshot = snapshot,
                    items = view.day?.let { snapshot.dayItemsFor(it.id) }.orEmpty(),
                    flow = de.wanderlogplus.app.ui.trip.dayFlowFor(snapshot, date),
                    accent = Color(0xFFC0603A),
                    onOpenItem = null,
                    moveTagPrefix = moveTagPrefix,
                )
            }
        }
    }

    private fun withItems(f: (de.wanderlogplus.shared.model.DayItem) -> de.wanderlogplus.shared.model.DayItem): TripSnapshot =
        Fixtures.trip().let { b -> b.copy(dayItems = b.dayItems.map(f)) }

    @Test fun estimatedTimesHaveATilde() {
        show(withItems { it.copy(fixedTime = null) }, "2027-04-02")
        rule.onAllNodes(hasText("~", substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("an estimated time", it.isNotEmpty()) }
        rule.onNodeWithTag("time-fixed-i-2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun fixedTimeShowsPin() {
        show(Fixtures.trip(), "2027-04-02")
        rule.onNodeWithTag("timeline-label-12:30", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("time-fixed-i-3", useUnmergedTree = true).assertExists()
    }

    @Test fun lateWarning() {
        show(withItems { if (it.id == "i-3") it.copy(fixedTime = "09:10") else it }, "2027-04-02")
        rule.onNode(hasText("Min zu spät", substring = true) and hasAnyAncestor(hasTestTag("time-warning-i-3")), useUnmergedTree = true).assertExists()
    }

    @Test fun startCardShowsDayStart() {
        show(Fixtures.trip(), "2027-04-02")
        rule.onNode(hasText("ab 09:00", substring = true) and hasAnyAncestor(hasTestTag("timeline-start")), useUnmergedTree = true).assertExists()
    }

    @Test fun durationTexts() {
        assertEquals("45 Min", durationText(45))
        assertEquals("2 Std", durationText(120))
        assertEquals("1 Std 30 Min", durationText(90))
    }

    @Test fun programCardShowsStayDuration() {
        val base = withItems { if (it.id == "i-3") it.copy(stayMinutes = 90) else it }
        val snap = base.copy(dayItems = base.dayItems + de.wanderlogplus.shared.model.DayItem("n-1", "d-2", "a9", de.wanderlogplus.shared.model.DayItemKind.NOTE, text = "Bento kaufen"))
        show(snap, "2027-04-02")
        rule.onNode(hasText("1 Std 30 Min", substring = true) and hasAnyAncestor(hasTestTag("day-item-i-3")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Min", substring = true) and hasAnyAncestor(hasTestTag("day-item-n-1")), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun noNightOnCheckOutDay() {
        val snap = Fixtures.trip()
        // 2027-04-06 is the last stay's check-out day: the query still reports the stay, but nobody sleeps there.
        assertEquals("st-kueste", snap.queries()!!.days().first { it.date == "2027-04-06" }.stay?.id)
        show(snap, "2027-04-06")
        rule.onNodeWithTag("timeline-night").assertDoesNotExist()
        rule.onNodeWithTag("movement-0").assertExists()
    }

    @Test fun aMovingDayRunsFromCheckOutOverTheTrainToCheckIn() {
        val base = Fixtures.trip()
        val d = "2027-04-04"
        val snap = base.copy(
            stays = base.stays.map { if (it.id == "st-berg") it.copy(checkOutTime = "10:00") else it.copy(checkInTime = "15:00") },
            // The beach market (coast) is listed first, the temple (mountain town) second: the region decides the side.
            dayItems = base.dayItems +
                de.wanderlogplus.shared.model.DayItem("m-1", "d-4", "a0", de.wanderlogplus.shared.model.DayItemKind.PLACE, "p-markt") +
                de.wanderlogplus.shared.model.DayItem("m-2", "d-4", "a1", de.wanderlogplus.shared.model.DayItemKind.PLACE, "p-tempel"),
        )
        val flow = de.wanderlogplus.app.ui.trip.dayFlowFor(snap, d)
        rule.setContent {
            WanderlogPlusTheme {
                androidx.compose.material3.Surface {
                    DayTimeline(
                        snapshot = snap, items = snap.dayItemsFor("d-4"), flow = flow, accent = Color(0xFFC0603A), onOpenItem = null,
                        itemTagPrefix = "row-",
                        legs = { leg -> androidx.compose.material3.Text("Weg ${leg.key}", modifier = androidx.compose.ui.Modifier.testTag("way-${leg.key}")) },
                    )
                }
            }
        }
        rule.onNode(hasText("Check-out bis 10:00", substring = true) and hasAnyAncestor(hasTestTag("timeline-start")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Check-in ab 15:00 · Übernachtung") and hasAnyAncestor(hasTestTag("timeline-night")), useUnmergedTree = true).assertExists()
        val top = { tag: String -> rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("temple before the train", top("row-m-2") < top("movement-0"))
        assertTrue("market after the train", top("movement-0") < top("row-m-1"))
        listOf("way-m-2", "way-ride:TRAIN:tr-1", "way-m-1", "way-night").forEach { rule.onNodeWithTag(it, useUnmergedTree = true).assertExists() }
        rule.onRoot().recordScreenshot("day-moving-light")
    }

    @Test fun nightOnRegularDay() {
        show(Fixtures.trip(), "2027-04-05")
        rule.onNode(hasText("Hotel Brandung") and hasAnyAncestor(hasTestTag("timeline-night"))).assertExists()
    }

    @Test fun movementsAreTimeSortedAndUseThePrefix() {
        val base = Fixtures.trip()
        val d = "2027-04-05"
        // Listed out of order on purpose: a late flight first, then an early and an undated-time train.
        val snap = base.copy(
            flights = base.flights + Flight("fl-late", airlineCode = "TF", number = "9", depart = Endpoint("Spät", date = d, time = "19:30"), arrive = Endpoint("Ziel")),
            trains = base.trains +
                Train("tr-none", depart = Endpoint("Ohne Zeit", date = d), arrive = Endpoint("Ziel")) +
                Train("tr-early", depart = Endpoint("Früh", date = d, time = "06:05"), arrive = Endpoint("Ziel")),
        )
        val ids = movementsOn(snap.flights, snap.trains, d).map {
            when (it) {
                is Movement.FlightMove -> it.flight.id
                is Movement.TrainMove -> it.train.id
            }
        }
        assertEquals(listOf("tr-early", "fl-late", "tr-none"), ids)
        show(snap, d, moveTagPrefix = "x-move-")
        listOf("Früh → Ziel", "Spät → Ziel", "Ohne Zeit → Ziel").forEachIndexed { i, title ->
            rule.onNode(hasText(title) and hasAnyAncestor(hasTestTag("x-move-$i"))).assertExists()
        }
        rule.onNodeWithTag("movement-0").assertDoesNotExist()
        val tops = (0..2).map { rule.onNodeWithTag("x-move-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test fun timesLineMarksOvernightArrival() {
        val overnight = Flight(
            "f", airlineCode = "NH", number = "204",
            depart = Endpoint("Frankfurt", "FRA", date = "2027-03-31", time = "11:15"),
            arrive = Endpoint("Tokio", "HND", date = "2027-04-01", time = "06:15"),
        )
        assertEquals("11:15 – 06:15 +1 · 12 Std. · NH 204", Movement.FlightMove(overnight).timesLine())
        // The Logistik list uses the same helper.
        assertEquals("11:15 – 06:15 +1 · 12 Std.", de.wanderlogplus.app.ui.logistics.movementTimes(Movement.FlightMove(overnight)))
        val sameDay = overnight.copy(arrive = overnight.arrive.copy(date = "2027-03-31"))
        // Arriving "before" leaving on the same day is impossible across these zones: no duration.
        assertEquals("11:15 – 06:15 · NH 204", Movement.FlightMove(sameDay).timesLine())
        val noArrivalDate = overnight.copy(arrive = overnight.arrive.copy(date = null))
        assertEquals("11:15 – 06:15 · 12 Std. · NH 204", Movement.FlightMove(noArrivalDate).timesLine())
        val train = Train(
            "t", carrier = "Nachtzug",
            depart = Endpoint("A", date = "2027-04-01", time = "22:00"),
            arrive = Endpoint("B", date = "2027-04-03", time = "07:00"),
        )
        assertEquals("22:00 – 07:00 +2 · 33 Std. · Nachtzug", Movement.TrainMove(train).timesLine())
    }
}
