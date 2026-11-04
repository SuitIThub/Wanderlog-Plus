package de.wanderlogplus.app.ui.budget

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.places.PlaceDetailScreen
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.query.BudgetCategories
import de.wanderlogplus.shared.sync.RateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class BudgetTest {
    @get:Rule val rule = createComposeRule()

    private class Recorder : BudgetActions {
        val saved = mutableListOf<Pair<Expense?, Expense>>()
        val deleted = mutableListOf<String>()
        var people: List<Traveler>? = null
        override fun save(before: Expense?, after: Expense) { saved += before to after }
        override fun delete(id: String) { deleted += id }
        override fun setTravelers(travelers: List<Traveler>) { people = travelers }
        override suspend fun rate(currency: String, date: String?) = RateInfo(currency, rate = 0.006, date = "2027-05-01")
    }

    private val c = Traveler("c", "Christian")
    private val s = Traveler("s", "Selina")

    private fun snapshot(expenses: List<Expense> = emptyList()) = TripSnapshot(
        trip = Trip("t", "Japan", "2027-05-08", "2027-05-20", travelers = listOf(c, s)),
        regions = emptyList(),
        places = mapOf("ph" to Place("ph", name = "Hotel Gion"), "pg" to Place("pg", name = "Moosgarten")),
        savedPlaces = listOf(SavedPlace("sg", "pg", category = PlaceCategory.SIGHT)),
        stays = listOf(Stay("st", "ph", checkIn = "2027-05-08", checkOut = "2027-05-12")),
        flights = emptyList(), trains = emptyList(), days = emptyList(), dayItems = emptyList(), notes = emptyList(), conflicts = emptyList(),
        expenses = expenses,
    )

    private val hotel = Expense(
        "e1", 400.0, "EUR", BudgetCategories.LODGING, "Hotel Gion", "2027-05-08", relatedId = "st",
        paidBy = "c", shares = listOf(ExpenseShare("c"), ExpenseShare("s")), status = PaymentStatus.OPEN,
    )
    private val ramen = Expense(
        "e2", 3000.0, "JPY", BudgetCategories.FOOD, "Ramen", "2027-05-09",
        paidBy = "s", shares = listOf(ExpenseShare("c"), ExpenseShare("s")), rate = 0.006,
    )

    @Test fun overviewShowsTotalsBalancesAndBooksTheSettlement() {
        val rec = Recorder()
        rule.setContent { WanderlogPlusTheme { BudgetScreen(snapshot(listOf(hotel, ramen)), "2027-05-10", rec) } }
        rule.onNodeWithTag("budget-total").assertExistsWithText("418,00 €")
        rule.onNodeWithTag("budget-open").assertExistsWithText("Beim Anbieter noch offen: 400,00 €")
        // Christian paid 400, his share is 209 → gets 191.
        rule.onNode(hasText("bekommt 191,00 €") and hasAnyAncestor(hasTestTag("budget-person-c")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("schuldet 191,00 €") and hasAnyAncestor(hasTestTag("budget-person-s")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Selina → Christian: 191,00 €") and hasAnyAncestor(hasTestTag("settle-0")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("≈ 18,00 €") and hasAnyAncestor(hasTestTag("cost-e2")), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Offen") and hasAnyAncestor(hasTestTag("cost-e1")), useUnmergedTree = true).assertExists()

        // "Komplett bezahlt" books the whole amount at once.
        rule.onNodeWithTag("settle-0-book").tap()
        val (before, t) = rec.saved.single()
        assertNull(before)
        assertEquals(Expense.KIND_TRANSFER, t.kind)
        assertEquals(Triple("s", "c", 191.0), Triple(t.paidBy, t.toTraveler, t.amount))
    }

    @Test fun partialRepaymentOpensTheFormWithWhatIsStillOwed() {
        val rec = Recorder()
        rule.setContent { WanderlogPlusTheme { BudgetScreen(snapshot(listOf(hotel, ramen)), "2027-05-10", rec) } }
        rule.onNodeWithTag("settle-0-part").tap()
        rule.onNode(hasText("Offen: 191,00 €"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("expense-save").assertIsNotEnabled() // no amount yet
        rule.onNodeWithTag("expense-amount").performTextInput("50")
        rule.onNodeWithTag("expense-save").tap()
        val t = rec.saved.single().second
        assertEquals(Triple("s", "c", 50.0), Triple(t.paidBy, t.toTraveler, t.amount))
        assertEquals(Expense.KIND_TRANSFER, t.kind)
    }

    @Test fun aPaymentCanBeEnteredWithoutASuggestion() {
        val rec = Recorder()
        rule.setContent { WanderlogPlusTheme { BudgetScreen(snapshot(), "2027-05-10", rec) } }
        rule.onNode(hasText("Alles ausgeglichen.")).assertExists()
        rule.onNodeWithTag("transfer-add").tap()
        rule.onNodeWithTag("expense-amount").performTextInput("20")
        rule.onNodeWithTag("expense-save").tap()
        val t = rec.saved.single().second
        assertEquals(Triple("s", "c", 20.0), Triple(t.paidBy, t.toTraveler, t.amount))
    }

    @Test fun formSplitsByPercentAndChecksTheSum() {
        val rec = Recorder()
        rule.setContent { WanderlogPlusTheme { BudgetScreen(snapshot(), "2027-05-10", rec) } }
        rule.onNodeWithTag("budget-add").tap()
        rule.onNodeWithTag("expense-description").performTextInput("Kaiseki")
        rule.onNodeWithTag("expense-amount").performTextInput("200")
        rule.onNodeWithTag("expense-mode-percent").tap()
        rule.onNodeWithTag("expense-share-c-value").performTextClearance()
        rule.onNodeWithTag("expense-share-c-value").performTextInput("70")
        rule.onNode(hasText("Die Prozente ergeben 120 % statt 100 %.")).assertExists()
        rule.onNodeWithTag("expense-save").assertIsNotEnabled()
        rule.onNodeWithTag("expense-share-s-value").performTextClearance()
        rule.onNodeWithTag("expense-share-s-value").performTextInput("30")
        // Selina already gave Christian her part.
        rule.onNodeWithTag("expense-share-s-repaid").performTextInput("60")
        rule.onNodeWithTag("expense-status-open").tap()
        rule.onNodeWithTag("expense-save").assertIsEnabled().tap()
        val e = rec.saved.single().second
        assertEquals("Kaiseki", e.description)
        assertEquals(200.0, e.amount, 0.0)
        assertEquals(SplitMode.PERCENT, e.splitMode)
        assertEquals(listOf(ExpenseShare("c", 70.0), ExpenseShare("s", 30.0, repaid = 60.0)), e.shares)
        assertEquals(PaymentStatus.OPEN, e.status)
        assertEquals("c", e.paidBy)
    }

    @Test fun foreignCurrencyShowsTheRateAndATypedRateIsManual() {
        val rec = Recorder()
        rule.setContent { WanderlogPlusTheme { BudgetScreen(snapshot(listOf(ramen)), "2027-05-10", rec) } }
        rule.onNodeWithTag("cost-e2").tap()
        rule.onNode(hasText("Kurs vom Sa, 01.05.") or hasText("Kurs vom ?"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("expense-rate").performTextClearance()
        rule.onNodeWithTag("expense-rate").performTextInput("0,0058")
        rule.onNodeWithTag("expense-save").tap()
        val (before, after) = rec.saved.single()
        assertEquals(ramen, before)
        assertEquals(0.0058, after.rate!!, 1e-9)
        assertEquals(true, after.rateManual)
    }

    @Test fun placeDetailHasCostsWithTheLinkFilledIn() {
        val rec = Recorder()
        val linked = Expense("e3", 15.0, "EUR", BudgetCategories.SIGHTSEEING, "Eintritt", relatedId = "sg", paidBy = "c", shares = listOf(ExpenseShare("c")))
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalBudgetActions provides rec) {
                    PlaceDetailScreen(snapshot(listOf(linked, hotel)), "sg", onBack = null, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, today = "2027-05-10")
                }
            }
        }
        rule.onNodeWithTag("costs-total").assertExistsWithText("15,00 €")
        rule.onNode(hasTestTag("cost-e1")).assertDoesNotExist()
        rule.onNodeWithTag("costs-add").tap()
        rule.onNodeWithTag("expense-amount").performTextInput("8")
        rule.onNodeWithTag("expense-save").tap()
        val e = rec.saved.single().second
        assertEquals("sg", e.relatedId)
        assertEquals(BudgetCategories.SIGHTSEEING, e.category)
        assertEquals(listOf("c", "s"), e.shares.map { it.travelerId })
    }

    @Test fun peopleCanBeAddedButNotRemovedWhenUsed() {
        val rec = Recorder()
        rule.setContent { WanderlogPlusTheme { BudgetScreen(snapshot(listOf(hotel)), "2027-05-10", rec) } }
        rule.onNodeWithTag("budget-people").tap()
        rule.onNodeWithTag("person-0-remove").assertIsNotEnabled()
        rule.onNodeWithTag("person-add").tap()
        rule.onNodeWithTag("person-2-name").performTextInput("Mia")
        rule.onNodeWithTag("person-save").tap()
        assertEquals(listOf("Christian", "Selina", "Mia"), rec.people!!.map { it.name })
    }

    @Test fun rateRules() {
        val open = ramen.copy(status = PaymentStatus.OPEN)
        assertEquals("2027-05-10", RateRules.fetchFor(open, open.copy(status = PaymentStatus.PAID), "2027-05-10"))
        assertNull(RateRules.fetchFor(open, open.copy(status = PaymentStatus.PAID, rateManual = true), "2027-05-10"))
        assertNull(RateRules.fetchFor(ramen, ramen.copy(description = "x"), "2027-05-10"))
        assertEquals("2027-05-10", RateRules.fetchFor(null, ramen.copy(rate = null), "2027-05-10"))
        assertEquals("2027-05-10", RateRules.fetchFor(ramen, ramen.copy(currency = "USD"), "2027-05-10"))
        assertNull(RateRules.fetchFor(null, hotel, "2027-05-10"))
    }

    /** Touch does not reach bottom sheets under Robolectric: click through semantics. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.tap() = performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsWithText(text: String) =
        assert(hasText(text))
}
