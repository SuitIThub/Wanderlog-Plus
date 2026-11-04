package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.ExpenseShare
import de.wanderlogplus.shared.model.PaymentStatus
import de.wanderlogplus.shared.model.SplitMode
import de.wanderlogplus.shared.model.Traveler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BudgetMathTest {
    private val c = Traveler("c", "Christian")
    private val s = Traveler("s", "Selina")
    private val m = Traveler("m", "Mia")

    private fun cost(
        amount: Double, paidBy: String? = "c", mode: SplitMode = SplitMode.EQUAL, shares: List<ExpenseShare> = listOf(ExpenseShare("c"), ExpenseShare("s")),
        currency: String = "EUR", rate: Double? = null, category: String? = "lodging", status: PaymentStatus = PaymentStatus.PAID, deposit: Double? = null,
    ) = Expense("e", amount, currency, category, paidBy = paidBy, splitMode = mode, shares = shares, rate = rate, status = status, deposit = deposit)

    @Test
    fun `parts in all modes add up to the amount`() {
        assertEquals(listOf("c" to 3.34, "s" to 3.33, "m" to 3.33), BudgetMath.parts(cost(10.0, shares = listOf(ExpenseShare("c"), ExpenseShare("s"), ExpenseShare("m")))))
        assertEquals(listOf("c" to 70.0, "s" to 30.0), BudgetMath.parts(cost(100.0, mode = SplitMode.PERCENT, shares = listOf(ExpenseShare("c", 70.0), ExpenseShare("s", 30.0)))))
        assertEquals(listOf("c" to 3.34, "s" to 6.66), BudgetMath.parts(cost(10.0, mode = SplitMode.PERCENT, shares = listOf(ExpenseShare("c", 33.4), ExpenseShare("s", 66.6)))))
        assertEquals(listOf("c" to 20.0, "s" to 80.0), BudgetMath.parts(cost(100.0, mode = SplitMode.AMOUNT, shares = listOf(ExpenseShare("c", 20.0), ExpenseShare("s", 80.0)))))
        assertEquals(emptyList(), BudgetMath.parts(cost(10.0, shares = emptyList())))
    }

    @Test
    fun `validation names what is wrong`() {
        assertEquals(emptyList(), BudgetMath.validate(cost(100.0)))
        assertEquals(listOf("Die Prozente ergeben 90 % statt 100 %."), BudgetMath.validate(cost(100.0, mode = SplitMode.PERCENT, shares = listOf(ExpenseShare("c", 60.0), ExpenseShare("s", 30.0)))))
        assertEquals(listOf("Die Beträge ergeben 90 statt 100."), BudgetMath.validate(cost(100.0, mode = SplitMode.AMOUNT, shares = listOf(ExpenseShare("c", 60.0), ExpenseShare("s", 30.0)))))
        assertTrue("Wer hat bezahlt?" in BudgetMath.validate(cost(10.0, paidBy = null)))
        assertTrue("Niemand ist beteiligt." in BudgetMath.validate(cost(10.0, shares = emptyList())))
        assertTrue("Zurückgezahlt ist mehr als der Anteil." in BudgetMath.validate(cost(10.0, shares = listOf(ExpenseShare("c"), ExpenseShare("s", repaid = 6.0)))))
        assertTrue("Anzahlung fehlt oder ist zu hoch." in BudgetMath.validate(cost(10.0, status = PaymentStatus.DEPOSIT)))
        val transfer = Expense("t", 50.0, "EUR", kind = Expense.KIND_TRANSFER, paidBy = "s", toTraveler = "s")
        assertEquals(listOf("Zahler und Empfänger sind gleich."), BudgetMath.validate(transfer))
    }

    @Test
    fun `balances count shares, repayments, transfers and rates and add up to zero`() {
        val hotel = cost(200.0) // Christian pays, 50/50
        val ramen = cost(3000.0, paidBy = "s", currency = "JPY", rate = 0.006, category = "food") // 18 € by Selina, 50/50
        val taxi = cost(40.0, shares = listOf(ExpenseShare("c"), ExpenseShare("s", repaid = 20.0))) // Selina already repaid her part
        val back = Expense("t", 50.0, "EUR", kind = Expense.KIND_TRANSFER, paidBy = "s", toTraveler = "c")
        val unknown = cost(5000.0, currency = "JPY") // no rate: left out
        val b = BudgetMath.balances(listOf(hotel, ramen, taxi, back, unknown))
        // Christian: +100 (hotel) −9 (ramen) +0 (taxi repaid) −50 (transfer) = 41
        assertEquals(41.0, b["c"])
        assertEquals(-41.0, b["s"])
        assertEquals(listOf(Settlement("s", "c", 41.0)), BudgetMath.settle(b))

        val sum = BudgetMath.summary(listOf(hotel, ramen, taxi, back, unknown), listOf(c, s))
        assertEquals(258.0, sum.total)
        assertEquals(mapOf("lodging" to 240.0, "food" to 18.0), sum.byCategory)
        assertEquals(1, sum.withoutRate)
        val chris = sum.people.first { it.traveler.id == "c" }
        assertEquals(240.0, chris.paid)
        assertEquals(129.0, chris.share)
        assertEquals(41.0, chris.balance)
    }

    @Test
    fun `settlements pair the biggest debts with the biggest credits`() {
        val r = BudgetMath.settle(mapOf("c" to 90.0, "s" to -60.0, "m" to -30.0, "x" to 0.2))
        assertEquals(listOf(Settlement("s", "c", 60.0), Settlement("m", "c", 30.0)), r)
        assertEquals(emptyList(), BudgetMath.settle(mapOf("c" to 0.3, "s" to -0.3)))
    }

    @Test
    fun `open at the vendor counts open costs and the rest after a deposit`() {
        val sum = BudgetMath.summary(
            listOf(cost(100.0, status = PaymentStatus.OPEN), cost(300.0, status = PaymentStatus.DEPOSIT, deposit = 100.0), cost(50.0)),
            listOf(c, s),
        )
        assertEquals(300.0, sum.openAtVendor)
        assertEquals(450.0, sum.total)
        assertEquals("Unterkunft", BudgetCategories.label("lodging"))
        assertEquals("Sonstiges", BudgetCategories.label("spaceship"))
    }
}
