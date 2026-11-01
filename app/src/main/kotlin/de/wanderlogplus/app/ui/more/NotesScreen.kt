package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Note

const val NOTE_ROUTE = "mehr/note/{noteId}"
fun noteRoute(noteId: String) = "mehr/note/${android.net.Uri.encode(noteId)}"

fun noteTitle(n: Note) = n.title.ifBlank { "Ohne Titel" }

@Composable
fun NotesScreen(snapshot: TripSnapshot, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val notes = remember(snapshot) { snapshot.notes.sortedWith(compareBy({ it.sortKey }, { it.id })) }
    if (notes.isEmpty()) {
        Text("Keine Notizen", modifier.padding(16.dp).testTag("notes-empty"))
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("notes-list")) {
        items(notes, key = { it.id }) { n ->
            Text(
                noteTitle(n),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(n.id) }.padding(16.dp).testTag("note-${n.id}"),
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun NoteDetailScreen(snapshot: TripSnapshot, noteId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val note = snapshot.notes.firstOrNull { it.id == noteId }
    Column(modifier.fillMaxSize()) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        if (note == null) {
            Text("Notiz nicht gefunden", Modifier.padding(16.dp))
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(noteTitle(note), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
                MarkdownText(note.body)
            }
        }
    }
}
