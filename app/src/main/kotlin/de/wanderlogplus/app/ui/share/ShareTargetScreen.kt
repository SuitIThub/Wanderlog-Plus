package de.wanderlogplus.app.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Searchable, grouped list of attach targets. [fileCount] only shapes the title. */
@Composable
fun ShareTargetScreen(targets: List<ShareTarget>, fileCount: Int, onPick: (ShareTarget) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(targets, query) { filterTargets(targets, query) }
    Column(modifier.fillMaxSize()) {
        Text(
            if (fileCount == 1) "Anhängen an …" else "$fileCount Dateien anhängen an …",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Suchen") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("share-search"),
        )
        if (shown.isEmpty()) {
            Text("Keine Treffer", Modifier.padding(16.dp).testTag("share-empty"))
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("share-list")) {
                ShareGroup.entries.forEach { group ->
                    val inGroup = shown.filter { it.group == group }
                    if (inGroup.isNotEmpty()) {
                        item(key = "h-${group.name}") {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(inGroup.size, key = { "${group.name}-${inGroup[it].id}" }) { i ->
                            val t = inGroup[i]
                            Column(
                                Modifier.fillMaxWidth().clickable { onPick(t) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp).testTag("share-${t.id}"),
                            ) {
                                Text(t.label, style = MaterialTheme.typography.bodyLarge)
                                t.subtitle?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
