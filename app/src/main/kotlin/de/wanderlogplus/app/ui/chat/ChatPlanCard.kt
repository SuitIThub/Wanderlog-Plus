package de.wanderlogplus.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.client.PlanOp
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatPlan
import de.wanderlogplus.shared.sync.ChatPlanStep

/** A row the user can accept or reject: additions, removals and new times. */
internal fun ChatPlanStep.isChange(): Boolean =
    action == ChatPlanStep.ADD || action == ChatPlanStep.REMOVE || (action == ChatPlanStep.KEEP && time != null && time != oldTime)

/**
 * The editor steps for [plan] with the rows [accepted] (indexes): kept items in plan order (a rejected new time stays
 * as it was), accepted additions, accepted removals; a rejected removal keeps the item at its row.
 */
internal fun planOps(plan: ChatPlan, accepted: Set<Int>): Pair<List<PlanOp>, Set<String>> {
    val ops = mutableListOf<PlanOp>()
    val remove = mutableSetOf<String>()
    plan.steps.forEachIndexed { i, st ->
        val ok = i in accepted
        when (st.action) {
            ChatPlanStep.KEEP -> st.itemId?.let { ops += PlanOp.Keep(it, st.time.takeIf { ok && st.isChange() }) }
            ChatPlanStep.REMOVE -> st.itemId?.let { if (ok) remove += it else ops += PlanOp.Keep(it) }
            ChatPlanStep.ADD -> if (ok) {
                val saved = st.savedPlaceId
                val lat = st.lat
                val lng = st.lng
                when {
                    saved != null -> ops += PlanOp.AddSaved(saved, st.time)
                    lat != null && lng != null -> ops += PlanOp.AddFound(
                        st.title, lat, lng, st.address,
                        st.category?.let { c -> PlaceCategory.entries.firstOrNull { it.name == c } } ?: PlaceCategory.SIGHT,
                        st.time, note = "Aus dem Reise-Chat",
                    )
                }
            }
        }
    }
    return ops to remove
}

/**
 * A day plan from the chat: every row with a checkbox (all accepted at first), additions marked "Neu", removals in red,
 * struck through and marked "Entfällt". "Plan übernehmen" writes the accepted rows (the program before is saved in the
 * day's history), "Zurücksetzen" restores it.
 */
@Composable
internal fun ChatPlanCard(c: ChatCard, key: String, actions: ChatActions?) {
    val plan = c.plan ?: return
    val changeable = plan.steps.indices.filter { plan.steps[it].isChange() }
    var accepted by rememberSaveable(key) { mutableStateOf(changeable.toList()) }
    var appliedAt by rememberSaveable(key) { mutableStateOf<Long?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("chat-plan-card-$key"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            plan.check?.let { check ->
                val fine = check.startsWith("Keine Probleme")
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (fine) Icons.Filled.Check else Icons.Filled.Warning, contentDescription = null,
                        tint = if (fine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Text(
                        if (fine) "Plan-Check: keine Probleme" else "Plan-Check:\n$check",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            plan.steps.forEachIndexed { i, st ->
                PlanRow(st, checked = i in accepted, enabled = appliedAt == null, tag = "plan-step-$key-$i") { on ->
                    accepted = if (on) accepted + i else accepted - i
                }
            }
            if (actions != null) {
                val applied = appliedAt
                if (applied == null) {
                    val n = accepted.size
                    Button(
                        onClick = {
                            val (ops, remove) = planOps(plan, accepted.toSet())
                            val at = System.currentTimeMillis()
                            actions.applyPlan(plan.date, ops, remove, at)
                            appliedAt = at
                        },
                        modifier = Modifier.fillMaxWidth().testTag("plan-apply-$key"),
                    ) { Text(if (n == 0) "Reihenfolge übernehmen" else "Plan übernehmen ($n ${if (n == 1) "Änderung" else "Änderungen"})") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("Übernommen", Modifier.padding(start = 6.dp).weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(
                            onClick = { actions.restorePlan(plan.date, applied); appliedAt = null },
                            modifier = Modifier.testTag("plan-reset-$key"),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Zurücksetzen", Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRow(st: ChatPlanStep, checked: Boolean, enabled: Boolean, tag: String, onCheck: (Boolean) -> Unit) {
    val removing = st.action == ChatPlanStep.REMOVE && checked
    val bg = when {
        removing -> MaterialTheme.colorScheme.errorContainer
        st.action == ChatPlanStep.ADD && checked -> MaterialTheme.colorScheme.secondaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (st.isChange()) {
                Checkbox(checked = checked, onCheckedChange = onCheck, enabled = enabled, modifier = Modifier.testTag("$tag-check"))
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val time = when {
                        st.action == ChatPlanStep.KEEP && st.time != null && st.time != st.oldTime && checked -> (st.oldTime?.let { "$it → " } ?: "") + st.time
                        st.action == ChatPlanStep.KEEP -> st.oldTime
                        else -> st.time ?: st.oldTime
                    }
                    time?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp), textDecoration = if (removing) TextDecoration.LineThrough else null)
                    }
                    Text(
                        st.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (removing) TextDecoration.LineThrough else null,
                        color = if (removing) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                st.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            when {
                st.action == ChatPlanStep.ADD -> Badge(if (checked) "Neu" else "nicht übernehmen", MaterialTheme.colorScheme.primary)
                st.action == ChatPlanStep.REMOVE && checked -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text("Entfällt", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
                st.action == ChatPlanStep.REMOVE -> Badge("bleibt", MaterialTheme.colorScheme.onSurfaceVariant)
                st.isChange() && !checked -> Badge("Zeit bleibt", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}
