package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.model.Criteria
import de.wanderlogplus.shared.sync.AssessConfig
import kotlinx.coroutines.delay

const val AI_SETTINGS_ROUTE = "mehr/ki-kriterien"

/** Longest extra prompt the server accepts. */
const val MAX_EXTRA_PROMPT = 1000

/** [selected] with [key] switched on or off, in catalogue order. */
fun toggleCriterion(selected: List<String>, key: String): List<String> {
    val next = if (key in selected) selected - key else selected + key
    return Criteria.ALL.map { it.key }.filter { it in next }
}

/**
 * "KI-Kriterien": which criteria the assistant scores (1–5) when it judges or researches places, plus free
 * instructions. Every change is stored right away; the text after a short pause.
 */
@Composable
fun AiSettingsScreen(config: AssessConfig, onChange: (AssessConfig) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(config)
    var draft by rememberSaveable { mutableStateOf(config.extraPrompt) }
    LaunchedEffect(draft) {
        delay(500)
        if (draft != current.extraPrompt) onChange(current.copy(extraPrompt = draft))
    }
    Column(modifier.fillMaxWidth().padding(16.dp).testTag("ai-settings"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Nach diesen Kriterien bewertet die KI jeden Ort mit 1–5 Punkten und zieht daraus ein Gesamtfazit. " +
                "Gilt für Einschätzung, Recherche und Reels.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Kriterien (${config.criteria.size} von ${Criteria.ALL.size})", Modifier.weight(1f))
            TextButton(onClick = { onChange(config.copy(criteria = Criteria.DEFAULT)) }, modifier = Modifier.testTag("criteria-default")) { Text("Standard") }
            TextButton(onClick = { onChange(config.copy(criteria = Criteria.ALL.map { it.key })) }, modifier = Modifier.testTag("criteria-all")) { Text("Alle") }
        }
        SurfaceCard {
            Column {
                Criteria.ALL.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                    val on = c.key in config.criteria
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(value = on, role = Role.Checkbox) { onChange(config.copy(criteria = toggleCriterion(config.criteria, c.key))) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("criterion-${c.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(c.label, style = MaterialTheme.typography.bodyLarge)
                            Text(c.question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (config.criteria.isEmpty()) {
            Text("Ohne Auswahl gelten die Standardkriterien.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        SectionLabel("Zusätzliche Anweisungen", Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(MAX_EXTRA_PROMPT) },
            modifier = Modifier.fillMaxWidth().testTag("ai-extra-prompt"),
            placeholder = { Text("z. B. Wir mögen ruhige Orte und lokales Essen, keine Freizeitparks.") },
            supportingText = { Text("${draft.length}/$MAX_EXTRA_PROMPT") },
            minLines = 3,
        )
    }
}
