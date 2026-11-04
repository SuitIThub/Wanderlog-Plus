package de.wanderlogplus.app.ui.budget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Train
import androidx.compose.ui.graphics.vector.ImageVector
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.logistics.flightCardTitle
import de.wanderlogplus.app.ui.logistics.trainTitle
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.PaymentStatus
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.BudgetCategories
import de.wanderlogplus.shared.query.BudgetMath
import de.wanderlogplus.shared.sync.EntityType
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Currencies offered in the form (others stay possible from imports). */
val BUDGET_CURRENCIES = listOf("EUR", "JPY", "USD", "GBP", "CHF", "KRW", "TWD", "CNY", "THB")

/** "1.234,56 €", "3.000 ¥", "12,50 USD". */
fun moneyText(amount: Double, currency: String): String {
    val cur = currency.ifBlank { BudgetMath.BASE_CURRENCY }.uppercase()
    val digits = runCatching { Currency.getInstance(cur).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    val f = NumberFormat.getNumberInstance(Locale.GERMAN).apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
    }
    val symbol = when (cur) {
        "EUR" -> "€"
        "JPY" -> "¥"
        "USD" -> "$"
        "GBP" -> "£"
        else -> cur
    }
    return f.format(amount) + " " + symbol
}

fun eurText(amount: Double): String = moneyText(amount, BudgetMath.BASE_CURRENCY)

/** Number as typed in a field ("12,50" or "12.50"); null when it is no number. */
fun parseAmount(text: String): Double? = text.trim().replace(" ", "").let { t ->
    val normal = if (t.count { it == ',' } == 1 && t.count { it == '.' } >= 1) t.replace(".", "").replace(',', '.') else t.replace(',', '.')
    normal.toDoubleOrNull()
}

/** A number for an input field: "12,5", "3000". */
fun amountField(v: Double?): String = when {
    v == null -> ""
    v == Math.floor(v) && kotlin.math.abs(v) < 1e12 -> v.toLong().toString()
    else -> v.toBigDecimal().stripTrailingZeros().toPlainString().replace('.', ',')
}

fun categoryIcon(key: String?): ImageVector = when (BudgetCategories.of(key)) {
    BudgetCategories.LODGING -> Icons.Filled.Hotel
    BudgetCategories.FLIGHTS -> Icons.Filled.Flight
    BudgetCategories.PUBLIC_TRANSIT -> Icons.Filled.Train
    BudgetCategories.CAR_RENTAL -> Icons.Filled.DirectionsCar
    BudgetCategories.FOOD -> Icons.Filled.Restaurant
    BudgetCategories.DRINKS -> Icons.Filled.LocalBar
    BudgetCategories.SIGHTSEEING -> Icons.Filled.Museum
    BudgetCategories.ACTIVITIES -> Icons.Filled.Attractions
    BudgetCategories.SHOPPING -> Icons.Filled.ShoppingBag
    BudgetCategories.GAS -> Icons.Filled.LocalGasStation
    BudgetCategories.GROCERIES -> Icons.Filled.LocalGroceryStore
    else -> Icons.Filled.Payments
}

fun statusLabel(s: PaymentStatus): String = when (s) {
    PaymentStatus.OPEN -> "Offen"
    PaymentStatus.DEPOSIT -> "Angezahlt"
    PaymentStatus.PAID -> "Bezahlt"
}

fun travelerName(snapshot: TripSnapshot, id: String?): String =
    id?.let { i -> snapshot.trip?.travelers?.firstOrNull { it.id == i }?.name?.takeIf { it.isNotBlank() } } ?: "?"

/** What a cost belongs to, for the related id of a stay, flight, train or saved place. */
data class CostTarget(val id: String, val type: EntityType, val label: String, val category: String, val date: String?)

/** Everything a cost can be linked to, in a stable order (stays, flights, trains, planned places). */
fun costTargets(snapshot: TripSnapshot): List<CostTarget> {
    val stays = snapshot.stays.sortedBy { it.checkIn }.map { s ->
        CostTarget(s.id, EntityType.STAY, snapshot.places[s.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft", BudgetCategories.LODGING, s.checkIn.ifBlank { null })
    }
    val flights = snapshot.flights.sortedBy { it.depart.date.orEmpty() }.map { f ->
        CostTarget(f.id, EntityType.FLIGHT, flightCardTitle(f), BudgetCategories.FLIGHTS, f.depart.date)
    }
    val trains = snapshot.trains.sortedBy { it.depart.date.orEmpty() }.map { t ->
        CostTarget(t.id, EntityType.TRAIN, trainTitle(t) + listOf(t.depart.name, t.arrive.name).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(" → ", prefix = ": ").orEmpty(), BudgetCategories.PUBLIC_TRANSIT, t.depart.date)
    }
    val places = snapshot.savedPlaces.sortedBy { snapshot.places[it.placeId]?.name.orEmpty() }.map { s ->
        val date = snapshot.days.filter { d -> snapshot.dayItems.any { it.dayId == d.id && it.placeId == s.placeId } }.minOfOrNull { it.date }
        CostTarget(s.id, EntityType.SAVED_PLACE, snapshot.places[s.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Ort", categoryFor(s.category), date)
    }
    return stays + flights + trains + places
}

fun categoryFor(c: PlaceCategory): String = when (c) {
    PlaceCategory.FOOD -> BudgetCategories.FOOD
    PlaceCategory.SHOPPING -> BudgetCategories.SHOPPING
    PlaceCategory.NIGHTLIFE -> BudgetCategories.DRINKS
    PlaceCategory.SIGHT -> BudgetCategories.SIGHTSEEING
    PlaceCategory.OTHER -> BudgetCategories.OTHER
}

/** One line for a cost: its description, else what it belongs to, else the category. */
fun costTitle(snapshot: TripSnapshot, e: Expense, targets: List<CostTarget> = costTargets(snapshot)): String =
    e.description.takeIf { it.isNotBlank() } ?: targets.firstOrNull { it.id == e.relatedId }?.label ?: BudgetCategories.label(e.category)

fun dateText(date: String?): String? = date?.takeIf { it.isNotBlank() }?.let { runCatching { dayLabel(it.take(10)) }.getOrNull() ?: it }
