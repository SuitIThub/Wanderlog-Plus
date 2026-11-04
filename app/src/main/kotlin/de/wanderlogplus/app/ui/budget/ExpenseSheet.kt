package de.wanderlogplus.app.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.ExpenseShare
import de.wanderlogplus.shared.model.PaymentStatus
import de.wanderlogplus.shared.model.SplitMode
import de.wanderlogplus.shared.query.BudgetCategories
import de.wanderlogplus.shared.query.BudgetMath
import kotlinx.coroutines.launch

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/** A new cost with sensible defaults: everyone shares equally, paid, today. */
fun newExpense(snapshot: TripSnapshot, today: String, relatedId: String? = null, category: String? = null, date: String? = null): Expense {
    val people = snapshot.trip?.travelers.orEmpty()
    return Expense(
        id = "", amount = 0.0, currency = BudgetMath.BASE_CURRENCY, category = category ?: BudgetCategories.OTHER,
        date = date ?: today, relatedId = relatedId, paidBy = people.firstOrNull()?.id,
        shares = people.map { ExpenseShare(it.id) },
    )
}

/** A settlement payment [from] → [to] (default: the first suggestion). */
fun newTransfer(today: String, from: String?, to: String?, amount: Double = 0.0) = Expense(
    id = "", amount = amount, currency = BudgetMath.BASE_CURRENCY, description = "Ausgleich", date = today,
    kind = Expense.KIND_TRANSFER, paidBy = from, toTraveler = to,
)

/**
 * Edit sheet for a cost or a settlement payment: amount and currency (with the rate), category, date, what it
 * belongs to, who paid, how it is split (equal / percent / amounts) with what each one repaid, and the status with
 * the vendor. Saving needs a consistent split ([BudgetMath.validate]).
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ExpenseSheet(
    snapshot: TripSnapshot,
    initial: Expense,
    isNew: Boolean,
    actions: BudgetActions,
    onDismiss: () -> Unit,
    /** For a repayment: what is still owed (in euros), shown under the amount. */
    openAmount: Double? = null,
) {
    val people = snapshot.trip?.travelers.orEmpty()
    val targets = remember(snapshot) { costTargets(snapshot) }
    var e by remember(initial) { mutableStateOf(initial) }
    var amountText by remember(initial) { mutableStateOf(amountField(initial.amount.takeIf { it > 0 })) }
    var rateText by remember(initial) { mutableStateOf(amountField(initial.rate)) }
    var depositText by remember(initial) { mutableStateOf(amountField(initial.deposit)) }
    val values = remember(initial) { mutableStateOf(initial.shares.associate { it.travelerId to amountField(it.value.takeIf { v -> v > 0 }) }) }
    val repaid = remember(initial) { mutableStateOf(initial.shares.associate { it.travelerId to amountField(it.repaid.takeIf { v -> v > 0 }) }) }
    var rateNote by remember(initial) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun current(): Expense {
        val shares = e.shares.map { s ->
            s.copy(value = parseAmount(values.value[s.travelerId].orEmpty()) ?: 0.0, repaid = parseAmount(repaid.value[s.travelerId].orEmpty()) ?: 0.0)
        }
        return e.copy(
            amount = parseAmount(amountText) ?: 0.0,
            shares = if (e.isTransfer) emptyList() else shares,
            deposit = if (e.status == PaymentStatus.DEPOSIT) parseAmount(depositText) else null,
            description = e.description.trim(),
            date = e.date?.trim()?.ifBlank { null },
        )
    }

    val draft = current()
    val problems = BudgetMath.validate(draft).toMutableList().apply {
        if (draft.date != null && !DATE.matches(draft.date!!)) add("Datum bitte als JJJJ-MM-TT.")
    }
    val title = when {
        e.isTransfer -> if (isNew) "Ausgleich eintragen" else "Ausgleich"
        isNew -> "Kosten hinzufügen"
        else -> "Kosten bearbeiten"
    }

    EditSheet(title, onDismiss = onDismiss) {
        if (people.isEmpty()) {
            Text("Lege zuerst unter Budget › Personen die Mitreisenden an.", color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            e.description, { e = e.copy(description = it) }, label = { Text("Beschreibung") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("expense-description"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                amountText, { amountText = it }, label = { Text("Betrag") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = openAmount?.let { o -> { Text("Offen: ${eurText(o)}") } },
                modifier = Modifier.weight(1f).testTag("expense-amount"),
            )
            LabeledDropdown(
                "expense-currency", "Währung", e.currency,
                (BUDGET_CURRENCIES + e.currency).distinct().filter { it.isNotBlank() }.map { it to it },
                onSelect = { c ->
                    if (!c.equals(e.currency, ignoreCase = true)) {
                        e = e.copy(currency = c, rate = null, rateDate = null, rateManual = false)
                        rateText = ""
                    }
                },
                modifier = Modifier.width(120.dp),
            )
        }
        if (RateRules.isForeign(e)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    rateText,
                    { t -> rateText = t; e = e.copy(rate = parseAmount(t), rateManual = true) },
                    label = { Text("Kurs: € pro 1 ${e.currency}") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = {
                        Text(
                            rateNote ?: when {
                                e.rate == null -> "Wird beim Speichern abgefragt"
                                e.rateManual -> "Von Hand gesetzt"
                                else -> "Kurs vom ${dateText(e.rateDate) ?: "?"}"
                            },
                        )
                    },
                    modifier = Modifier.weight(1f).testTag("expense-rate"),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val r = actions.rate(e.currency, null)
                            if (r == null) rateNote = "Kein Kurs verfügbar" else {
                                rateNote = null
                                e = e.copy(rate = r.rate, rateDate = r.date, rateManual = false)
                                rateText = amountField(r.rate)
                            }
                        }
                    },
                    modifier = Modifier.testTag("expense-rate-refresh"),
                ) { Text("Aktualisieren") }
            }
            val eur = draft.rate?.let { BudgetMath.cents(draft.amount * it) }
            if (eur != null && draft.amount > 0) Text("≈ ${eurText(eur)}", style = MaterialTheme.typography.bodySmall)
        }

        if (!e.isTransfer) {
            LabeledDropdown(
                "expense-category", "Kategorie", BudgetCategories.label(e.category),
                BudgetCategories.LABELS.toList(), onSelect = { e = e.copy(category = it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            e.date.orEmpty(), { e = e.copy(date = it) }, label = { Text("Datum (JJJJ-MM-TT)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("expense-date"),
        )
        if (!e.isTransfer) {
            val none = "Kein Bezug"
            LabeledDropdown(
                "expense-related", "Gehört zu", targets.firstOrNull { it.id == e.relatedId }?.label ?: none,
                listOf<Pair<String?, String>>(null to none) + targets.map { it.id to it.label },
                onSelect = { id -> e = e.copy(relatedId = id) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(if (e.isTransfer) "Von" else "Bezahlt von", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { p ->
                FilterChip(e.paidBy == p.id, { e = e.copy(paidBy = p.id) }, label = { Text(p.name) }, modifier = Modifier.testTag("expense-payer-${p.id}"))
            }
        }
        if (e.isTransfer) {
            Text("An", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { p ->
                    FilterChip(e.toTraveler == p.id, { e = e.copy(toTraveler = p.id) }, label = { Text(p.name) }, modifier = Modifier.testTag("expense-to-${p.id}"))
                }
            }
        } else {
            Text("Aufteilung", style = MaterialTheme.typography.titleSmall)
            val modes = listOf(SplitMode.EQUAL to "Gleich", SplitMode.PERCENT to "Prozent", SplitMode.AMOUNT to "Beträge")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = e.splitMode == m,
                        onClick = {
                            if (e.splitMode != m) {
                                // Start the new mode with an even split the user can change.
                                val ids = e.shares.map { it.travelerId }
                                val total = parseAmount(amountText) ?: 0.0
                                values.value = ids.associateWith { id ->
                                    when (m) {
                                        SplitMode.PERCENT -> amountField(BudgetMath.cents(100.0 / ids.size.coerceAtLeast(1)))
                                        SplitMode.AMOUNT -> amountField(BudgetMath.parts(e.copy(amount = total, splitMode = SplitMode.EQUAL)).toMap()[id])
                                        SplitMode.EQUAL -> ""
                                    }
                                }
                                e = e.copy(splitMode = m)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        modifier = Modifier.testTag("expense-mode-${m.name.lowercase()}"),
                    ) { Text(label) }
                }
            }
            val parts = BudgetMath.parts(draft).toMap()
            people.forEach { p ->
                val share = e.shares.firstOrNull { it.travelerId == p.id }
                Column(Modifier.fillMaxWidth().testTag("expense-share-${p.id}")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = share != null,
                            onCheckedChange = { on ->
                                e = e.copy(shares = if (on) e.shares + ExpenseShare(p.id) else e.shares.filter { it.travelerId != p.id })
                            },
                            modifier = Modifier.testTag("expense-share-${p.id}-check"),
                        )
                        Text(p.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        if (share != null && e.splitMode != SplitMode.EQUAL) {
                            OutlinedTextField(
                                values.value[p.id].orEmpty(), { t -> values.value = values.value + (p.id to t) },
                                label = { Text(if (e.splitMode == SplitMode.PERCENT) "%" else e.currency) }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(110.dp).testTag("expense-share-${p.id}-value"),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        parts[p.id]?.let { Text(moneyText(it, e.currency), style = MaterialTheme.typography.bodyMedium) }
                    }
                    if (share != null && draft.paidBy != null && p.id != draft.paidBy) {
                        OutlinedTextField(
                            repaid.value[p.id].orEmpty(), { t -> repaid.value = repaid.value + (p.id to t) },
                            label = { Text("Schon an ${travelerName(snapshot, draft.paidBy)} zurückgezahlt (${e.currency})") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(start = 48.dp).testTag("expense-share-${p.id}-repaid"),
                        )
                    }
                }
            }

            Text("Beim Anbieter", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentStatus.entries.forEach { s ->
                    FilterChip(e.status == s, { e = e.copy(status = s) }, label = { Text(statusLabel(s)) }, modifier = Modifier.testTag("expense-status-${s.name.lowercase()}"))
                }
            }
            if (e.status == PaymentStatus.DEPOSIT) {
                OutlinedTextField(
                    depositText, { depositText = it }, label = { Text("Angezahlt (${e.currency})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("expense-deposit"),
                )
            }
        }

        problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("expense-problem")) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (!isNew) {
                OutlinedButton(onClick = { actions.delete(initial.id); onDismiss() }, modifier = Modifier.testTag("expense-delete")) { Text("Löschen") }
            }
            Button(
                onClick = { actions.save(if (isNew) null else initial, current()); onDismiss() },
                enabled = problems.isEmpty(),
                modifier = Modifier.testTag("expense-save"),
            ) { Text("Speichern") }
        }
    }
}
