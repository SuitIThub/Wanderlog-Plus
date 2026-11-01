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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class DayTimelineTest {
    @get:Rule val rule = createComposeRule()

    private fun show(snapshot: TripSnapshot, date: String, moveTagPrefix: String = "movement-") {
        val view = snapshot.queries()!!.days().first { it.date == date }
        rule.setContent {
            WanderlogPlusTheme {
                DayTimeline(
                    snapshot = snapshot,
                    date = date,
                    items = view.day?.let { snapshot.dayItemsFor(it.id) }.orEmpty(),
                    movements = movementsOn(snapshot.flights, snapshot.trains, date),
                    stay = view.stay,
                    accent = Color(0xFFC0603A),
                    onOpenItem = null,
                    moveTagPrefix = moveTagPrefix,
                )
            }
        }
    }

    @Test fun noNightOnCheckOutDay() {
        val snap = Fixtures.trip()
        // 2027-04-06 is the last stay's check-out day: the query still reports the stay, but nobody sleeps there.
        assertEquals("st-kueste", snap.queries()!!.days().first { it.date == "2027-04-06" }.stay?.id)
        show(snap, "2027-04-06")
        rule.onNodeWithTag("timeline-night").assertDoesNotExist()
        rule.onNodeWithTag("movement-0").assertExists()
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
        assertEquals("11:15 – 06:15 +1 · NH 204", Movement.FlightMove(overnight).timesLine())
        // The Logistik list uses the same helper.
        assertEquals("11:15 – 06:15 +1", de.wanderlogplus.app.ui.logistics.timesText(overnight.depart, overnight.arrive))
        val sameDay = overnight.copy(arrive = overnight.arrive.copy(date = "2027-03-31"))
        assertEquals("11:15 – 06:15 · NH 204", Movement.FlightMove(sameDay).timesLine())
        val noArrivalDate = overnight.copy(arrive = overnight.arrive.copy(date = null))
        assertEquals("11:15 – 06:15 · NH 204", Movement.FlightMove(noArrivalDate).timesLine())
        val train = Train(
            "t", carrier = "Nachtzug",
            depart = Endpoint("A", date = "2027-04-01", time = "22:00"),
            arrive = Endpoint("B", date = "2027-04-03", time = "07:00"),
        )
        assertEquals("22:00 – 07:00 +2 · Nachtzug", Movement.TrainMove(train).timesLine())
    }
}
