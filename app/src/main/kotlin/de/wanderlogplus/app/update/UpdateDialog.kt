package de.wanderlogplus.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.wanderlogplus.app.ui.MarkdownText
import java.io.File

/** "12,3 MB". */
fun megabytes(bytes: Long): String = String.format(java.util.Locale.GERMAN, "%.1f MB", bytes / 1_048_576.0)

/**
 * The update dialogs: changelog with "Aktualisieren", progress while downloading, the install step and failures.
 * [debugBuild]: this installation is signed with the debug key, so the release cannot be installed over it.
 * Tags: `update-dialog`, `update-notes`, `update-install`, `update-later`, `update-skip`, `update-progress`.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    currentVersion: String,
    debugBuild: Boolean,
    onUpdate: (AppRelease) -> Unit,
    onLater: () -> Unit,
    onSkip: (AppRelease) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (UpdateState.ReadyToInstall) -> Unit,
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
                    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("update-notes")) {
                        if (state.release.notes.isBlank()) Text("Keine Änderungsliste.") else MarkdownText(state.release.notes)
                    }
                    TextButton(onClick = { onSkip(state.release) }, modifier = Modifier.testTag("update-skip")) { Text("Diese Version überspringen") }
                }
            },
            confirmButton = { TextButton(onClick = { onUpdate(state.release) }, modifier = Modifier.testTag("update-install")) { Text("Aktualisieren") } },
            dismissButton = { TextButton(onClick = onLater, modifier = Modifier.testTag("update-later")) { Text("Später") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag("update-progress"),
            title = { Text("Update wird geladen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${megabytes(state.done)} von ${megabytes(state.total)}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelDownload) { Text("Abbrechen") } },
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("update-ready"),
            title = { Text("Update ${state.release.version} bereit") },
            text = { Text("Android fragt gleich, ob die App aktualisiert werden soll. Beim ersten Mal muss das Installieren aus dieser App einmal erlaubt werden.") },
            confirmButton = { TextButton(onClick = { onInstall(state) }, modifier = Modifier.testTag("update-run-install")) { Text("Installieren") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Später") } },
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

/**
 * Hands the downloaded APK to Android's installer. Returns false when the user first has to allow installs from this
 * app (the matching settings page is opened then; afterwards call again).
 */
fun installApk(context: Context, apk: File): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(settings) } catch (e: ActivityNotFoundException) { }
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    return true
}
