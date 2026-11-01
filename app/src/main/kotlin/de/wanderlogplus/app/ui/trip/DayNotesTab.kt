package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsFor
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.sync.EntityType

/** Notizen & Dateien tab: the day note (Markdown, edited inline) and the day's attachments. */
@Composable
internal fun DayNotesTab(
    snapshot: TripSnapshot,
    date: String,
    day: Day?,
    note: String,
    accent: Color,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    attachments: AttachmentActions?,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionLabel("Tagesnotiz", color = accent)
        Surface(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).testTag("day-note-field"),
                        minLines = 3,
                    )
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCancel) { Text("Abbrechen") }
                        Button(onClick = onSave) { Text("Speichern") }
                    }
                } else {
                    if (note.isNotBlank()) {
                        MarkdownText(note, Modifier.padding(end = 8.dp).testTag("day-note"))
                    } else {
                        Text("Keine Notiz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onStartEdit, modifier = Modifier.align(Alignment.End)) { Text("Bearbeiten") }
                }
            }
        }
        if (attachments != null) {
            // Same section label as "Tagesnotiz" instead of the section's own plain title.
            SectionLabel("Anhänge", color = accent)
            AttachmentsFor(
                day?.let { snapshot.attachmentsFor(EntityType.DAY, it.id) }.orEmpty(),
                attachments,
                onAdd = { attachments.onAddToDay(date) },
                showTitle = false,
            )
        }
    }
}
