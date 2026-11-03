package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import de.wanderlogplus.app.data.TripZone
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
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
    tripZone: TripZone = TripZone.JAPAN,
    onTripZone: (TripZone) -> Unit = {},
    remindersEnabled: Boolean = true,
    onRemindersEnabled: (Boolean) -> Unit = {},
    /** Null: read from the system (re-checked on resume). */
    notificationsAllowed: Boolean? = null,
    /** Installed version and "Nach Updates suchen" (null hides the button). */
    appVersion: String = "",
    onCheckUpdates: (() -> Unit)? = null,
    /** Opens "KI-Kriterien"; null hides the section. */
    onOpenAiSettings: (() -> Unit)? = null,
    /** "Kartenbilder neu laden": drops the cached route snapshots so they are rendered again. */
    onReloadMaps: () -> Unit = {},
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var systemAllowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(context) {
        systemAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val allowed = notificationsAllowed ?: systemAllowed
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings-screen"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel("App")
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Version: ${appVersion.ifBlank { "–" }}", modifier = Modifier.testTag("settings-version"))
                onCheckUpdates?.let { check ->
                    OutlinedButton(onClick = check, modifier = Modifier.testTag("settings-check-updates")) { Text("Nach Updates suchen") }
                }
            }
        }

        SectionLabel("Server")
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Adresse: ${info.serverUrl ?: "–"}")
                Text("Token: ${maskToken(info.token)}")
                OutlinedButton(onClick = onChangeServer) { Text("Server ändern") }
            }
        }

        SectionLabel("Synchronisation")
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Zuletzt synchronisiert: " + (status.lastSyncAt?.let { relativeAgo(it, now) } ?: "noch nie"))
                status.lastError?.let { Text("Fehler: $it", color = MaterialTheme.colorScheme.error) }
                Text("Wartende Änderungen: ${status.pending}")
                Text("Geräte-ID: ${info.nodeId}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSyncNow, enabled = !status.running) { Text("Jetzt synchronisieren") }
                    OutlinedButton(onClick = { confirmReset = true }) { Text("Sync-Zustand zurücksetzen") }
                }
            }
        }

        SectionLabel("Zeitzone")
        SurfaceCard {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                for ((zone, label) in listOf(TripZone.JAPAN to "Japan (JST)", TripZone.DEVICE to "Gerätezeit verwenden")) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = tripZone == zone, role = Role.RadioButton, onClick = { onTripZone(zone) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tripZone == zone, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Text(label, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }

        SectionLabel("Erinnerungen")
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Erinnerungen an Fristen", Modifier.weight(1f))
                    Switch(checked = remindersEnabled, onCheckedChange = onRemindersEnabled)
                }
                if (remindersEnabled && !allowed) {
                    Text(
                        "Benachrichtigungen sind für die App ausgeschaltet.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        onOpenAiSettings?.let { open ->
            SectionLabel("KI-Einschätzung")
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Kriterien und eigene Anweisungen für Einschätzung, Recherche und Reels.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = open, modifier = Modifier.testTag("settings-ai")) { Text("KI-Kriterien") }
                }
            }
        }

        SectionLabel("Karten")
        SurfaceCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Gespeicherte Kartenbilder der Fahrten löschen und neu laden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onReloadMaps, modifier = Modifier.testTag("settings-reload-maps")) { Text("Kartenbilder neu laden") }
            }
        }
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
