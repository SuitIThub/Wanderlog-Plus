package de.wanderlogplus.app.ui.today

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.ReservationWindow
import de.wanderlogplus.shared.query.DeadlineState
import de.wanderlogplus.shared.query.DeadlineTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class DeadlineRowsTest {
    @get:Rule val rule = createComposeRule()

    private fun snap() = Fixtures.trip().let { s ->
        s.copy(
            trains = s.trains.map { it.copy(reservation = ReservationWindow(opens = "2027-02-20", by = "2027-03-20")) },
            flights = s.flights.map {
                when (it.id) {
                    "fl-1" -> it.copy(reservation = ReservationWindow(by = "2027-04-05")) // 35 days away
                    else -> it.copy(reservation = ReservationWindow(by = "2027-02-20")) // overdue
                }
            },
        )
    }

    @Test
    fun onlyNext30DaysPlusOverdue() {
        val rows = deadlineRows(snap(), "2027-03-01")
        assertEquals(DeadlineState.OVERDUE, rows.first().state)
        assertEquals("fl-2", rows.first().targetId)
        assertEquals(listOf("fl-2", "st-berg", "tr-1"), rows.map { it.targetId })
        assertEquals("Überfällig: reservieren bis Sa, 20.02.", rows.first().text)
        assertEquals("Jetzt reservierbar · bis Sa, 20.03.", rows.first { it.targetId == "tr-1" }.text)
        assertEquals(DeadlineTarget.STAY, rows.first { it.targetId == "st-berg" }.targetType)
    }

    @Test
    fun sameRuleDuringTheTrip() {
        val s = Fixtures.trip().let { s -> s.copy(stays = s.stays.map { if (it.id == "st-kueste") it.copy(cancellationDeadline = "2027-05-20") else it }) }
        val rows = deadlineRows(s, "2027-04-02")
        assertEquals(emptyList<String>(), rows.map { it.targetId }.filter { it == "st-kueste" })
    }

    @Test
    fun widgetFoldsAfterFour() {
        val rows = (1..6).map { DeadlineRow("2027-03-0$it", "Frist $it", "Text $it", "deadline-$it") }
        var opened: Pair<DeadlineTarget, String>? = null
        rule.setContent { WanderlogPlusTheme { DeadlinesWidget(rows, Color.Red, onOpen = { t, id -> opened = t to id }) } }
        rule.onNodeWithText("Frist 4").assertExists()
        rule.onNodeWithText("Frist 5").assertDoesNotExist()
        rule.onNodeWithText("Alle Fristen (6)").assertExists()
        rule.onNodeWithTag("deadlines-more").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Frist 6").assertExists()
    }

    @Test
    fun rowsOpenTheirTarget() {
        var opened: Pair<DeadlineTarget, String>? = null
        rule.setContent { WanderlogPlusTheme { DeadlinesWidget(deadlineRows(snap(), "2027-03-01"), Color.Red, onOpen = { t, id -> opened = t to id }) } }
        rule.onNodeWithTag("window-0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(DeadlineTarget.FLIGHT to "fl-2", opened)
    }
}
