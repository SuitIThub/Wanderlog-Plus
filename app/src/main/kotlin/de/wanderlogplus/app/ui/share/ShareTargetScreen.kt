package de.wanderlogplus.app.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.app.ui.design.SectionLabel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import de.wanderlogplus.app.ui.design.EmptyHint

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
            EmptyHint("Keine Treffer", Modifier.padding(16.dp).testTag("share-empty"))
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("share-list"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                ShareGroup.entries.forEach { group ->
                    val inGroup = shown.filter { it.group == group }
                    if (inGroup.isNotEmpty()) {
                        item(key = "h-${group.name}") {
                            SectionLabel(group.label, Modifier.padding(horizontal = 16.dp))
                        }
                        items(inGroup.size, key = { "${group.name}-${inGroup[it].id}" }) { i ->
                            val t = inGroup[i]
                            val photo = group == ShareGroup.PLACE || group == ShareGroup.STAY
                            PhotoCard(
                                title = t.label,
                                subtitle = t.subtitle,
                                imageUrl = if (photo) t.imageUrl else null,
                                placeholderSymbol = group.symbol,
                                accent = MaterialTheme.colorScheme.primary,
                                onClick = { onPick(t) },
                                modifier = Modifier.padding(horizontal = 16.dp).testTag("share-${t.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}
