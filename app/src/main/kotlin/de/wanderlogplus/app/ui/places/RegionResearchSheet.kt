package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceStatus

/** One region in "Regionen recherchieren": its saved places (not dropped) and those never researched. */
data class RegionChoice(val key: String, val name: String, val all: List<String>, val fresh: List<String>)

/** Regions in tile order, „Ohne Region“ last; regions without places are left out. */
fun regionChoices(snapshot: TripSnapshot): List<RegionChoice> {
    fun choice(key: String, name: String): RegionChoice? {
        val places = savedPlacesIn(snapshot, key).filter { it.status != PlaceStatus.DROPPED }
        if (places.isEmpty()) return null
        return RegionChoice(key, name, places.map { it.id }, places.filter { (it.dossier?.researchedAt ?: 0L) == 0L }.map { it.id })
    }
    return regionTileOrder(snapshot).mapNotNull { choice(it.id, it.name) } + listOfNotNull(choice(NO_REGION, "Ohne Region"))
}

/**
 * "Regionen recherchieren": a check per region („Kyoto · 12 Orte, 5 neu“), „Alle auswählen“, then „Nur
 * unrecherchierte“ or „Alle“ with summed counts and costs. [onStart] gets one batch (region name, place ids) per chosen
 * region in the shown order; regions without places for the chosen button are left out.
 */
@Composable
fun RegionResearchSheet(choices: List<RegionChoice>, onStart: (List<Pair<String, List<String>>>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val chosen = choices.filter { it.key in selected }
    val fresh = chosen.sumOf { it.fresh.size }
    val all = chosen.sumOf { it.all.size }
    fun start(pick: (RegionChoice) -> List<String>) {
        val batches = chosen.map { it.name to pick(it) }.filter { it.second.isNotEmpty() }
        onDismiss()
        if (batches.isNotEmpty()) onStart(batches)
    }
    EditSheet("Regionen recherchieren", onDismiss = onDismiss) {
        Text(
            "Die KI sucht für jeden Ort im Netz nach Geheimtipps, versteckten Ecken und Hinweisen und bewertet ihn nach deinen KI-Kriterien. " +
                "Jede Region wird nacheinander recherchiert; das läuft im Hintergrund weiter.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = { selected = if (selected.size == choices.size) emptySet() else choices.map { it.key }.toSet() },
                modifier = Modifier.testTag("research-select-all"),
            ) { Text(if (selected.size == choices.size && choices.isNotEmpty()) "Keine auswählen" else "Alle auswählen") }
        }
        Column {
            choices.forEach { c ->
                val on = c.key in selected
                Row(
                    Modifier.fillMaxWidth().clickable { selected = if (on) selected - c.key else selected + c.key }
                        .padding(vertical = 2.dp).testTag("research-region-${c.key}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(
                        "${c.name} · ${c.all.size} " + (if (c.all.size == 1) "Ort" else "Orte") + ", ${c.fresh.size} neu",
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Button(
            onClick = { start { it.fresh } }, enabled = fresh > 0,
            modifier = Modifier.fillMaxWidth().testTag("research-new"),
        ) { Text("Nur unrecherchierte ($fresh · ${researchCostHint(fresh.coerceAtLeast(1))})") }
        FilledTonalButton(
            onClick = { start { it.all } }, enabled = all > 0,
            modifier = Modifier.fillMaxWidth().testTag("research-all"),
        ) { Text("Alle ($all · ${researchCostHint(all.coerceAtLeast(1))})") }
    }
}
