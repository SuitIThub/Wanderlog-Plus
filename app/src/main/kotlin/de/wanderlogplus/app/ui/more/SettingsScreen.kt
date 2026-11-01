package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.ui.relativeAgo

/** What the settings screen shows about this installation. */
data class SettingsInfo(val serverUrl: String?, val token: String?, val nodeId: String)

/** Token shown as bullets plus its last 4 characters; short tokens are fully hidden. */
fun maskToken(token: String?): String = when {
    token.isNullOrEmpty() -> "–"
    token.length <= 4 -> "••••"
    else -> "••••" + token.takeLast(4)
}

@Composable
fun SettingsScreen(
    info: SettingsInfo,
    status: SyncStatus,
    onChangeServer: () -> Unit,
    onSyncNow: () -> Unit,
    onResetSync: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Text("Adresse: ${info.serverUrl ?: "–"}")
        Text("Token: ${maskToken(info.token)}")
        OutlinedButton(onClick = onChangeServer) { Text("Server ändern") }

        Text("Synchronisation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text("Zuletzt synchronisiert: " + (status.lastSyncAt?.let { relativeAgo(it, now) } ?: "noch nie"))
        status.lastError?.let { Text("Fehler: $it", color = MaterialTheme.colorScheme.error) }
        Text("Wartende Änderungen: ${status.pending}")
        Text("Geräte-ID: ${info.nodeId}")
        Button(onClick = onSyncNow, enabled = !status.running) { Text("Jetzt synchronisieren") }
        OutlinedButton(onClick = { confirmReset = true }) { Text("Sync-Zustand zurücksetzen") }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Sync-Zustand zurücksetzen?") },
            text = { Text("Beim nächsten Sync wird der komplette Stand vom Server neu geladen. Lokale Änderungen bleiben erhalten.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; onResetSync() }) { Text("Zurücksetzen") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Abbrechen") } },
        )
    }
}
