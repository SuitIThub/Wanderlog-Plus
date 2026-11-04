package de.wanderlogplus.app.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.Ids
import de.wanderlogplus.shared.model.PaymentStatus
import de.wanderlogplus.shared.model.Traveler
import de.wanderlogplus.shared.query.BudgetCategories
import de.wanderlogplus.shared.query.BudgetMath
import kotlin.math.abs

/** Which sheet is open: a cost (new or existing) or the people. */
private sealed interface BudgetSheet {
    data class Edit(val expense: Expense, val isNew: Boolean) : BudgetSheet
    data object People : BudgetSheet
}

/**
 * The trip budget: total, what is still open with vendors, per person paid/share/balance, the settlement
 * suggestion (with "Als bezahlt eintragen"), the categories and the list of all costs (filter by category and
 * person). Tapping a cost edits it; "Kosten hinzufügen" adds one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(snapshot: TripSnapshot, today: String, actions: BudgetActions, modifier: Modifier = Modifier) {
    val people = snapshot.trip?.travelers.orEmpty()
    val summary = remember(snapshot) { BudgetMath.summary(snapshot.expenses, people) }
    val targets = remember(snapshot) { costTargets(snapshot) }
    var sheet by remember { mutableStateOf<BudgetSheet?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gesamt", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(eurText(summary.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("budget-total"))
                }
                TextButton(onClick = { sheet = BudgetSheet.People }, modifier = Modifier.testTag("budget-people")) {
                    Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Personen")
                }
            }
            if (summary.openAtVendor > 0) {
                Text("Beim Anbieter noch offen: ${eurText(summary.openAtVendor)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("budget-open"))
            }
            if (summary.withoutRate > 0) {
                Text(
                    if (summary.withoutRate == 1) "1 Kosten ohne Kurs – nicht mitgerechnet" else "${summary.withoutRate} Kosten ohne Kurs – nicht mitgerechnet",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("budget-without-rate"),
                )
            }

            if (summary.people.isNotEmpty()) {
                SectionLabel("Personen")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary.people.forEach { p ->
                        SurfaceCard(Modifier.testTag("budget-person-${p.traveler.id}")) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.traveler.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text("bezahlt ${eurText(p.paid)} · Anteil ${eurText(p.share)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val b = p.balance
                                Text(
                                    when {
                                        abs(b) < BudgetMath.SETTLED_EUR -> "ausgeglichen"
                                        b > 0 -> "bekommt ${eurText(b)}"
                                        else -> "schuldet ${eurText(-b)}"
                                    },
                                    color = when {
                                        abs(b) < BudgetMath.SETTLED_EUR -> MaterialTheme.colorScheme.onSurfaceVariant
                                        b > 0 -> Color(0xFF2E7D32)
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            val transfers = snapshot.expenses.filter { it.isTransfer }.sortedByDescending { it.date.orEmpty() }
            if (summary.settlements.isNotEmpty() || transfers.isNotEmpty()) {
                SectionLabel("Ausgleich")
                summary.settlements.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().testTag("settle-$i"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Handshake, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("${travelerName(snapshot, s.from)} → ${travelerName(snapshot, s.to)}: ${eurText(s.amount)}", Modifier.weight(1f))
                        TextButton(onClick = { sheet = BudgetSheet.Edit(newTransfer(today, s.from, s.to, s.amount), true) }, modifier = Modifier.testTag("settle-$i-book")) {
                            Text("Als bezahlt eintragen")
                        }
                    }
                }
                transfers.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { sheet = BudgetSheet.Edit(t, false) }.padding(vertical = 6.dp).testTag("transfer-${t.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${travelerName(snapshot, t.paidBy)} → ${travelerName(snapshot, t.toTraveler)}" + (dateText(t.date)?.let { " · $it" } ?: ""),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(moneyText(t.amount, t.currency), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (summary.byCategory.isNotEmpty()) {
                SectionLabel("Kategorien")
                summary.byCategory.forEach { (key, sum) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("budget-category-$key")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(categoryIcon(key), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(BudgetCategories.label(key), Modifier.weight(1f))
                            Text(eurText(sum), fontWeight = FontWeight.Medium)
                        }
                        LinearProgressIndicator(
                            progress = { if (summary.total > 0) (sum / summary.total).toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            SectionLabel("Alle Kosten")
            val costs = snapshot.expenses.filter { !it.isTransfer }
            val usedCategories = costs.map { BudgetCategories.of(it.category) }.distinct()
            if (usedCategories.size > 1 || people.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    usedCategories.forEach { c ->
                        FilterChip(category == c, { category = if (category == c) null else c }, label = { Text(BudgetCategories.label(c)) }, modifier = Modifier.testTag("filter-category-$c"))
                    }
                    people.forEach { p ->
                        FilterChip(person == p.id, { person = if (person == p.id) null else p.id }, label = { Text(p.name) }, modifier = Modifier.testTag("filter-person-${p.id}"))
                    }
                }
            }
            val shown = costs
                .filter { category == null || BudgetCategories.of(it.category) == category }
                .filter { person == null || it.paidBy == person || it.shares.any { s -> s.travelerId == person } }
                .sortedWith(compareByDescending<Expense> { it.date.orEmpty() }.thenBy { it.id })
            if (shown.isEmpty()) EmptyHint(if (costs.isEmpty()) "Noch keine Kosten eingetragen" else "Keine Kosten für diesen Filter", Modifier.padding(top = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { e -> CostRow(snapshot, e, targets) { sheet = BudgetSheet.Edit(e, false) } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { sheet = BudgetSheet.Edit(newExpense(snapshot, today), true) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Kosten hinzufügen") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("budget-add"),
        )
    }

    when (val s = sheet) {
        is BudgetSheet.Edit -> ExpenseSheet(snapshot, s.expense, s.isNew, actions, onDismiss = { sheet = null })
        BudgetSheet.People -> PeopleSheet(snapshot, actions, onDismiss = { sheet = null })
        null -> Unit
    }
}

/** One cost: icon, title with what it belongs to and who paid, the amount (and in euros) and its status. */
@Composable
fun CostRow(snapshot: TripSnapshot, e: Expense, targets: List<CostTarget> = costTargets(snapshot), onClick: () -> Unit) {
    SurfaceCard(Modifier.testTag("cost-${e.id}"), onClick = onClick) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(e.category), contentDescription = BudgetCategories.label(e.category), modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(costTitle(snapshot, e, targets), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val related = targets.firstOrNull { it.id == e.relatedId }?.label?.takeIf { it != e.description }
                Text(
                    listOfNotNull(dateText(e.date), related, e.paidBy?.let { "bezahlt von ${travelerName(snapshot, it)}" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(moneyText(e.amount, e.currency), fontWeight = FontWeight.Medium)
                if (RateRules.isForeign(e)) {
                    Text(BudgetMath.eur(e)?.let { "≈ ${eurText(it)}" } ?: "ohne Kurs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.status != PaymentStatus.PAID) {
                    Text(statusLabel(e.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cost-${e.id}-status"))
                }
            }
        }
    }
}

/** Add, rename and remove travellers; someone named in a cost cannot be removed. */
@Composable
private fun PeopleSheet(snapshot: TripSnapshot, actions: BudgetActions, onDismiss: () -> Unit) {
    val initial = snapshot.trip?.travelers.orEmpty()
    var list by remember { mutableStateOf(initial) }
    val used = remember(snapshot) {
        snapshot.expenses.flatMap { listOfNotNull(it.paidBy, it.toTraveler) + it.shares.map { s -> s.travelerId } }.toSet()
    }
    EditSheet("Personen", onDismiss = onDismiss) {
        list.forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    t.name, { n -> list = list.mapIndexed { j, x -> if (j == i) x.copy(name = n) else x } },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("person-$i-name"),
                )
                IconButton(onClick = { list = list.filterIndexed { j, _ -> j != i } }, enabled = t.id !in used, modifier = Modifier.testTag("person-$i-remove")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Entfernen")
                }
            }
        }
        OutlinedButton(onClick = { list = list + Traveler(Ids.newId(), "") }, modifier = Modifier.testTag("person-add")) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Person hinzufügen")
        }
        if (snapshot.trip == null) Text("Ohne Reise können keine Personen gespeichert werden.", color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = { actions.setTravelers(list.map { it.copy(name = it.name.trim()) }.filter { it.name.isNotBlank() }); onDismiss() },
                enabled = snapshot.trip != null && list != initial,
                modifier = Modifier.testTag("person-save"),
            ) { Text("Speichern") }
        }
    }
}

/**
 * "Kosten" of a stay, flight, train or place: the linked costs with their sum and "Kosten hinzufügen" with the link,
 * category and date filled in. Hidden without [LocalBudgetActions].
 */
@Composable
fun CostsSection(snapshot: TripSnapshot, relatedId: String, today: String, accent: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    val actions = LocalBudgetActions.current ?: return
    val targets = remember(snapshot) { costTargets(snapshot) }
    val target = targets.firstOrNull { it.id == relatedId }
    val linked = remember(snapshot, relatedId) { snapshot.expenses.filter { !it.isTransfer && it.relatedId == relatedId }.sortedBy { it.date.orEmpty() } }
    var sheet by remember { mutableStateOf<BudgetSheet.Edit?>(null) }
    Column(modifier.fillMaxWidth().testTag("costs-section"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Kosten", Modifier.weight(1f), color = accent)
            val total = linked.sumOf { BudgetMath.eur(it) ?: 0.0 }
            if (linked.isNotEmpty()) Text(eurText(total), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("costs-total"))
        }
        linked.forEach { e -> CostRow(snapshot, e, targets) { sheet = BudgetSheet.Edit(e, false) } }
        TextButton(
            onClick = { sheet = BudgetSheet.Edit(newExpense(snapshot, today, relatedId, target?.category, target?.date), true) },
            modifier = Modifier.testTag("costs-add"),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text("Kosten hinzufügen")
        }
    }
    sheet?.let { s -> ExpenseSheet(snapshot, s.expense, s.isNew, actions, onDismiss = { sheet = null }) }
}
