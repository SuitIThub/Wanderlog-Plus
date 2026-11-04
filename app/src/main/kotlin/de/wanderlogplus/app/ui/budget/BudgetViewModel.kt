package de.wanderlogplus.app.ui.budget

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import de.wanderlogplus.app.ui.trip.EditFn
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.PaymentStatus
import de.wanderlogplus.shared.model.Traveler
import de.wanderlogplus.shared.query.BudgetMath
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.RateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the budget screens can do; the app's [BudgetViewModel], a recorder in tests. */
interface BudgetActions {
    /** Saves [after] (new when [before] is null); fetches a rate first where the rules say so ([RateRules]). */
    fun save(before: Expense?, after: Expense)

    fun delete(id: String)

    fun setTravelers(travelers: List<Traveler>)

    /** Euros per unit of [currency] on [date] (null: latest); null when unknown or unreachable. */
    suspend fun rate(currency: String, date: String?): RateInfo?
}

/** The budget actions for detail screens (Kosten sections); null hides them. */
val LocalBudgetActions = compositionLocalOf<BudgetActions?> { null }

/**
 * When the app sets a rate by itself: a new cost in a foreign currency without one gets today's rate; switching to
 * "bezahlt" takes that day's rate once; a typed rate ([Expense.rateManual]) is never replaced.
 */
object RateRules {
    fun isForeign(e: Expense) = e.currency.isNotBlank() && !e.currency.equals(BudgetMath.BASE_CURRENCY, ignoreCase = true)

    /** The date to fetch a rate for, or null when [after] keeps its rate. */
    fun fetchFor(before: Expense?, after: Expense, today: String): String? {
        if (!isForeign(after) || after.rateManual) return null
        val becamePaid = after.status == PaymentStatus.PAID && before != null && before.status != PaymentStatus.PAID
        val currencyChanged = before != null && !before.currency.equals(after.currency, ignoreCase = true)
        return when {
            becamePaid -> today
            after.rate == null || currencyChanged -> today
            else -> null
        }
    }
}

class BudgetViewModel(
    private val edit: EditFn,
    private val writeScope: CoroutineScope,
    private val api: suspend () -> SyncApi?,
    private val today: () -> String,
) : ViewModel(), BudgetActions {
    private val _message = MutableStateFlow<String?>(null)

    /** A German note for a snackbar (e.g. no rate available); cleared by [messageShown]. */
    val message: StateFlow<String?> = _message

    fun messageShown() {
        _message.value = null
    }

    override suspend fun rate(currency: String, date: String?): RateInfo? = try {
        api()?.rate(currency, date)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override fun save(before: Expense?, after: Expense) {
        writeScope.launch {
            var e = after
            if (!RateRules.isForeign(e)) e = e.copy(rate = null, rateManual = false)
            RateRules.fetchFor(before, e, today())?.let { day ->
                val r = rate(e.currency, day)
                e = if (r != null) e.copy(rate = r.rate, rateDate = r.date)
                else e.also { _message.value = "Kein aktueller Kurs verfügbar – der bisherige bleibt." }
            }
            write { ed, _ ->
                if (before == null) ed.create(EntityType.EXPENSE, EntityCodec.fields(e.copy(id = "")))
                else ed.setChanged(EntityType.EXPENSE, before.id, EntityCodec.fields(before), EntityCodec.fields(e.copy(id = before.id)))
            }
        }
    }

    override fun delete(id: String) {
        write { ed, _ -> ed.delete(EntityType.EXPENSE, id) }
    }

    override fun setTravelers(travelers: List<Traveler>) {
        write { ed, s ->
            val trip = s.trip ?: return@write
            ed.set(EntityType.TRIP, trip.id, mapOf("travelers" to Json.encodeToJsonElement(ListSerializer(Traveler.serializer()), travelers)))
        }
    }

    private fun write(action: (de.wanderlogplus.client.LocalEditor, de.wanderlogplus.client.TripSnapshot) -> Unit): Job = writeScope.launch {
        try {
            edit(action)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = "Speichern fehlgeschlagen."
        }
    }
}
