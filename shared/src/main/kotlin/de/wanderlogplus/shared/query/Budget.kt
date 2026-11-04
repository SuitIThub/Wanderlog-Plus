package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.PaymentStatus
import de.wanderlogplus.shared.model.SplitMode
import de.wanderlogplus.shared.model.Traveler
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Budget categories (Wanderlog's keys) with German labels. */
object BudgetCategories {
    const val LODGING = "lodging"
    const val FLIGHTS = "flights"
    const val PUBLIC_TRANSIT = "publicTransit"
    const val CAR_RENTAL = "carRental"
    const val FOOD = "food"
    const val DRINKS = "drinks"
    const val SIGHTSEEING = "sightseeing"
    const val ACTIVITIES = "activities"
    const val SHOPPING = "shopping"
    const val GAS = "gas"
    const val GROCERIES = "groceries"
    const val OTHER = "other"

    val LABELS: Map<String, String> = linkedMapOf(
        LODGING to "Unterkunft",
        FLIGHTS to "Flüge",
        PUBLIC_TRANSIT to "Bahn & ÖPNV",
        CAR_RENTAL to "Mietwagen",
        FOOD to "Essen",
        DRINKS to "Getränke",
        SIGHTSEEING to "Sehenswürdigkeiten",
        ACTIVITIES to "Aktivitäten",
        SHOPPING to "Shopping",
        GAS to "Tanken",
        GROCERIES to "Einkäufe",
        OTHER to "Sonstiges",
    )

    /** A known key, else [OTHER]. */
    fun of(key: String?): String = key?.takeIf { it in LABELS } ?: OTHER

    fun label(key: String?): String = LABELS.getValue(of(key))
}

/** What a traveller paid, their share of the costs and the balance (positive: gets money back), in euros. */
data class PersonBudget(val traveler: Traveler, val paid: Double, val share: Double, val balance: Double)

/** "[from] pays [to] [amount] €" to settle the balances. */
data class Settlement(val from: String, val to: String, val amount: Double)

data class BudgetSummary(
    /** All costs (not transfers) with a known rate, in euros. */
    val total: Double,
    val byCategory: Map<String, Double>,
    /** Still to pay to hotels, airlines … (open, or the rest after a deposit), in euros. */
    val openAtVendor: Double,
    /** Costs that cannot be converted (no rate). */
    val withoutRate: Int,
    val people: List<PersonBudget>,
    val settlements: List<Settlement>,
)

/** Splits, balances and settlements of the trip's costs. Pure functions, amounts rounded to cents. */
object BudgetMath {
    const val BASE_CURRENCY = "EUR"

    /** Balances below this many euros count as settled. */
    const val SETTLED_EUR = 0.5

    fun cents(v: Double): Double = (v * 100).roundToLong() / 100.0

    /** Euros per unit of the expense's currency: its rate, 1.0 for euros, else null. */
    fun rateOf(e: Expense): Double? = e.rate ?: if (e.currency.isBlank() || e.currency.equals(BASE_CURRENCY, ignoreCase = true)) 1.0 else null

    fun eur(e: Expense, amount: Double = e.amount): Double? = rateOf(e)?.let { cents(amount * it) }

    /** Each participant's part in the expense's currency; the parts add up to the amount (rest on the first). */
    fun parts(e: Expense): List<Pair<String, Double>> {
        val people = e.shares.map { it.travelerId }.distinct()
        if (people.isEmpty()) return emptyList()
        val raw = when (e.splitMode) {
            SplitMode.EQUAL -> {
                val each = floor(e.amount * 100 / people.size) / 100
                people.map { it to each }
            }
            SplitMode.PERCENT -> e.shares.distinctBy { it.travelerId }.map { it.travelerId to cents(e.amount * it.value / 100) }
            SplitMode.AMOUNT -> e.shares.distinctBy { it.travelerId }.map { it.travelerId to cents(it.value) }
        }
        if (e.splitMode == SplitMode.AMOUNT) return raw
        val rest = cents(e.amount - raw.sumOf { it.second })
        if (e.splitMode == SplitMode.PERCENT && abs(e.shares.sumOf { it.value } - 100) > 0.01) return raw
        return raw.mapIndexed { i, (id, v) -> id to if (i == 0) cents(v + rest) else v }
    }

    /** German problems that block saving; empty when the expense is consistent. */
    fun validate(e: Expense): List<String> = buildList {
        if (e.amount <= 0) add("Betrag fehlt.")
        if (e.paidBy == null) add(if (e.isTransfer) "Wer hat gezahlt?" else "Wer hat bezahlt?")
        if (e.isTransfer) {
            if (e.toTraveler == null) add("An wen ging die Zahlung?")
            if (e.toTraveler != null && e.toTraveler == e.paidBy) add("Zahler und Empfänger sind gleich.")
            return@buildList
        }
        if (e.shares.isEmpty()) add("Niemand ist beteiligt.")
        when (e.splitMode) {
            SplitMode.PERCENT -> e.shares.sumOf { it.value }.let { if (abs(it - 100) > 0.01) add("Die Prozente ergeben ${fmt(it)} % statt 100 %.") }
            SplitMode.AMOUNT -> e.shares.sumOf { it.value }.let { if (abs(it - e.amount) > 0.005) add("Die Beträge ergeben ${fmt(it)} statt ${fmt(e.amount)}.") }
            SplitMode.EQUAL -> Unit
        }
        val part = parts(e).toMap()
        e.shares.filter { it.repaid > 0 && it.repaid - (part[it.travelerId] ?: 0.0) > 0.005 }.forEach { add("Zurückgezahlt ist mehr als der Anteil.") }
        if (e.status == PaymentStatus.DEPOSIT && (e.deposit == null || e.deposit <= 0 || e.deposit > e.amount)) add("Anzahlung fehlt oder ist zu hoch.")
    }.distinct()

    /** Balance per traveller id in euros (sum is 0): paid minus own share, plus repayments and transfers. */
    fun balances(expenses: List<Expense>): Map<String, Double> {
        val b = mutableMapOf<String, Double>()
        fun add(id: String?, v: Double) { if (id != null) b[id] = (b[id] ?: 0.0) + v }
        expenses.forEach { e ->
            val rate = rateOf(e) ?: return@forEach
            if (e.isTransfer) {
                add(e.paidBy, e.amount * rate)
                add(e.toTraveler, -e.amount * rate)
                return@forEach
            }
            val payer = e.paidBy ?: return@forEach
            val part = parts(e)
            if (part.isEmpty()) return@forEach
            add(payer, part.sumOf { it.second } * rate)
            part.forEach { (id, v) -> add(id, -v * rate) }
            e.shares.filter { it.travelerId != payer && it.repaid > 0 }.forEach { s ->
                add(s.travelerId, s.repaid * rate)
                add(payer, -s.repaid * rate)
            }
        }
        return b.mapValues { cents(it.value) }
    }

    /** Fewest payments that settle [balances]: biggest debtor pays biggest creditor, repeatedly. */
    fun settle(balances: Map<String, Double>): List<Settlement> {
        val debt = balances.filterValues { it < -SETTLED_EUR / 2 }.mapValues { -it.value }.toMutableMap()
        val credit = balances.filterValues { it > SETTLED_EUR / 2 }.toMutableMap()
        val out = mutableListOf<Settlement>()
        while (debt.isNotEmpty() && credit.isNotEmpty()) {
            val (d, dv) = debt.maxWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).toPair()
            val (c, cv) = credit.maxWith(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).toPair()
            val x = cents(minOf(dv, cv))
            if (x >= SETTLED_EUR) out += Settlement(d, c, x)
            if (dv - x < SETTLED_EUR) debt.remove(d) else debt[d] = dv - x
            if (cv - x < SETTLED_EUR) credit.remove(c) else credit[c] = cv - x
        }
        return out
    }

    fun summary(expenses: List<Expense>, travelers: List<Traveler>): BudgetSummary {
        val costs = expenses.filter { !it.isTransfer }
        val known = costs.filter { rateOf(it) != null }
        val byCategory = known.groupBy { BudgetCategories.of(it.category) }
            .mapValues { (_, l) -> cents(l.sumOf { eur(it) ?: 0.0 }) }
            .toList().sortedByDescending { it.second }.toMap()
        val open = known.sumOf { e ->
            when (e.status) {
                PaymentStatus.OPEN -> eur(e) ?: 0.0
                PaymentStatus.DEPOSIT -> eur(e, (e.amount - (e.deposit ?: 0.0)).coerceAtLeast(0.0)) ?: 0.0
                PaymentStatus.PAID -> 0.0
            }
        }
        val balances = balances(expenses)
        // People named in costs but missing from the list still count (e.g. removed later).
        val ids = (travelers.map { it.id } + balances.keys).distinct()
        val byId = travelers.associateBy { it.id }
        val people = ids.map { id ->
            val paid = known.filter { it.paidBy == id }.sumOf { e -> eur(e, parts(e).sumOf { it.second }) ?: 0.0 }
            val share = known.sumOf { e -> parts(e).filter { it.first == id }.sumOf { (_, v) -> eur(e, v) ?: 0.0 } }
            PersonBudget(byId[id] ?: Traveler(id, "Unbekannt"), cents(paid), cents(share), balances[id] ?: 0.0)
        }
        return BudgetSummary(
            total = cents(known.sumOf { eur(it) ?: 0.0 }),
            byCategory = byCategory,
            openAtVendor = cents(open),
            withoutRate = costs.size - known.size,
            people = people,
            settlements = settle(balances),
        )
    }

    private fun fmt(v: Double): String = if (v == floor(v)) v.toLong().toString() else String.format(java.util.Locale.GERMAN, "%.2f", v)
}
