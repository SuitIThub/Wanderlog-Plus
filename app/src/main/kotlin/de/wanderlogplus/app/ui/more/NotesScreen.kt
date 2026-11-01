package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Note

const val NOTE_ROUTE = "mehr/note/{noteId}"
fun noteRoute(noteId: String) = "mehr/note/${android.net.Uri.encode(noteId)}"

fun noteTitle(n: Note) = n.title.ifBlank { "Ohne Titel" }

/** First non-blank lines of a note body without Markdown markers, for the list preview. */
internal fun notePreview(body: String): String =
    body.lines().map { it.trim().trimStart('#', '-', '*', '>', ' ') }.filter { it.isNotEmpty() }.joinToString(" ")

@Composable
fun NotesScreen(snapshot: TripSnapshot, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val notes = remember(snapshot) { snapshot.notes.sortedWith(compareBy({ it.sortKey }, { it.id })) }
    if (notes.isEmpty()) {
        EmptyHint("Keine Notizen", modifier.padding(16.dp).testTag("notes-empty"))
        return
    }
    LazyColumn(
        modifier.fillMaxSize().testTag("notes-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(notes, key = { it.id }) { n ->
            val preview = notePreview(n.body)
            SurfaceCard(onClick = { onOpen(n.id) }, modifier = Modifier.testTag("note-${n.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(noteTitle(n), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (preview.isNotEmpty()) {
                        Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun NoteDetailScreen(snapshot: TripSnapshot, noteId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val note = snapshot.notes.firstOrNull { it.id == noteId }
    Column(modifier.fillMaxSize().testTag("note-detail")) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        if (note == null) {
            Text("Notiz nicht gefunden", Modifier.padding(16.dp))
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    noteTitle(note),
                    style = de.wanderlogplus.app.ui.HeroTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SurfaceCard { Column(Modifier.padding(16.dp)) { MarkdownText(note.body) } }
            }
        }
    }
}
