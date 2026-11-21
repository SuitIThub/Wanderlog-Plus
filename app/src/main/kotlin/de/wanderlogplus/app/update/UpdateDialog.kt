package de.wanderlogplus.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.MarkdownText

/** "12,3 MB". */
fun megabytes(bytes: Long): String = String.format(java.util.Locale.GERMAN, "%.1f MB", bytes / 1_048_576.0)

/**
 * The update dialogs: changelog with "Herunterladen" (the browser downloads the APK and Android installs it from the
 * download), "kein Update" and failures.
 * [debugBuild]: this installation is signed with the debug key, so the release cannot be installed over it.
 * Tags: `update-dialog`, `update-notes`, `update-install`, `update-later`, `update-skip`.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    debugBuild: Boolean,
    onUpdate: (AppRelease) -> Unit,
    onLater: () -> Unit,
    onSkip: (AppRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onLater,
            modifier = Modifier.testTag("update-dialog"),
            title = { Text("Update auf ${state.release.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Installiert: $currentVersion" + if (state.release.size > 0) " · Download ${megabytes(state.release.size)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (debugBuild) {
                        Text(
                            "Diese Testversion ist anders signiert als die veröffentlichte App. Vor dem Installieren muss sie einmal deinstalliert werden.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "„Herunterladen“ lädt das Update im Browser. Danach die Download-Benachrichtigung antippen und installieren.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("update-notes")) {
                        if (state.release.notes.isBlank()) Text("Keine Änderungsliste.") else MarkdownText(state.release.notes)
                    }
                    TextButton(onClick = { onSkip(state.release) }, modifier = Modifier.testTag("update-skip")) { Text("Diese Version überspringen") }
                }
            },
            confirmButton = { TextButton(onClick = { onUpdate(state.release) }, modifier = Modifier.testTag("update-install")) { Text("Herunterladen") } },
            dismissButton = { TextButton(onClick = onLater, modifier = Modifier.testTag("update-later")) { Text("Später") } },
        )
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Kein Update") },
            text = { Text("Version $currentVersion ist aktuell.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}
