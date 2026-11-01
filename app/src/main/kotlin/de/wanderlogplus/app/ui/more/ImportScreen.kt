package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.ImportLogInfo
import de.wanderlogplus.client.SyncApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ImportState(
    /** Null while the log is loading. */
    val log: List<ImportLogInfo>? = null,
    val running: Boolean = false,
    val message: String? = null,
)

/** Maps an API failure to a user message; never includes server text. */
internal fun importErrorText(e: Throwable): String = when ((e as? ApiException)?.kind) {
    ApiErrorKind.NOT_CONFIGURED -> "Auf dem Server ist kein Wanderlog-Trip konfiguriert."
    ApiErrorKind.NETWORK -> "Keine Verbindung zum Server."
    ApiErrorKind.UNAUTHORIZED -> "Token ungültig oder abgelaufen."
    else -> "Import fehlgeschlagen."
}

/**
 * [api] builds the [SyncApi] for the current server (null: none configured). [onImported] runs after a successful
 * import (the app requests a sync there).
 */
class ImportViewModel(
    private val api: suspend () -> SyncApi?,
    private val onImported: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state

    init { loadLog() }

    private suspend fun <T> call(block: suspend (SyncApi) -> T): Result<T> = try {
        val a = api()
        if (a == null) Result.failure(ApiException(ApiErrorKind.BAD_REQUEST)) else Result.success(block(a))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun loadLog() {
        viewModelScope.launch {
            call { it.importStatus() }
                .onSuccess { log -> _state.update { it.copy(log = log) } }
                .onFailure { e -> _state.update { it.copy(log = it.log ?: emptyList(), message = importErrorText(e)) } }
        }
    }

    fun import() {
        if (_state.value.running) return
        _state.update { it.copy(running = true, message = null) }
        viewModelScope.launch {
            val r = call { it.triggerImport() }
            _state.update { it.copy(running = false) }
            r.onSuccess {
                onImported()
                call { a -> a.importStatus() }.onSuccess { log -> _state.update { s -> s.copy(log = log) } }
            }.onFailure { e -> _state.update { it.copy(message = importErrorText(e)) } }
        }
    }
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

internal fun importTimeText(iso: String): String = try {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(TIME_FORMAT)
} catch (_: Exception) { iso }

@Composable
fun ImportScreen(vm: ImportViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsState()
    val log = s.log
    LazyColumn(
        modifier.fillMaxSize().testTag("import-log"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "trigger") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::import, enabled = !s.running) {
                    Text(if (s.running) "Importiere …" else "Aus Wanderlog importieren")
                }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("import-message")) }
            }
        }
        when {
            log == null -> Unit
            log.isEmpty() -> item(key = "none") { EmptyHint("Noch keine Importe") }
            else -> {
                item(key = "latest") {
                    Column {
                        SectionLabel("Letzter Import")
                        LatestImportCard(log.first())
                    }
                }
                if (log.size > 1) {
                    item(key = "history-label") { SectionLabel("Protokoll") }
                    items(log.drop(1)) { e -> LogRow(e) }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(e: ImportLogInfo) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(importTimeText(e.startedAt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Badge(BadgeSpec(if (e.ok) "OK" else "Fehler", if (e.ok) BadgeTone.Positive else BadgeTone.Warning))
    }
}

@Composable
private fun LatestImportCard(e: ImportLogInfo) {
    SurfaceCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusLine(e)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(e.created, "neu", Modifier.weight(1f))
                StatTile(e.updated, "geändert", Modifier.weight(1f))
                StatTile(e.conflicts, "Konflikte", Modifier.weight(1f))
                StatTile(e.removed, "entfernt", Modifier.weight(1f))
            }
            if (!e.ok) Text("Der Import ist auf dem Server fehlgeschlagen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LogRow(e: ImportLogInfo) {
    SurfaceCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusLine(e)
            Text(
                "neu ${e.created} · geändert ${e.updated} · Konflikte ${e.conflicts} · entfernt ${e.removed}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!e.ok) Text("Der Import ist auf dem Server fehlgeschlagen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}
