package de.wanderlogplus.app.ui.transit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TransitPickersTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun formats() {
        assertEquals("08:05", pickedTime(8, 5))
        assertEquals("23:59", pickedTime(23, 59))
        assertEquals("Do., 20. Mai 2027", dateLabel("2027-05-20"))
        assertEquals("x", dateLabel("x"))
        assertEquals("2027-05-20", pickedDate(dateMillis("2027-05-20")!!))
        assertEquals(null, dateMillis("kaputt"))
    }

    @Test
    fun timeAndDateOpenPickers() {
        var state by mutableStateOf(TransitScreenState(form = TransitForm(date = "2027-05-20", time = "09:00")))
        rule.setContent {
            WanderlogPlusTheme {
                TransitScreen(
                    "Weg", state, Color.Blue, chosen = null,
                    onEdit = { f -> state = state.copy(form = f(state.form)) }, onSearch = {}, onChoose = {}, onClear = {}, onBack = {},
                )
            }
        }
        rule.onNodeWithTag("transit-time").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("transit-time-ok").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(Regex("""\d{2}:\d{2}""").matches(state.form.time))
        rule.onNodeWithTag("transit-date").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("transit-date-ok").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("2027-05-20", state.form.date)
    }
}
