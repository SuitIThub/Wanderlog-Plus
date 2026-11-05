package de.wanderlogplus.app.ui.vault

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.wanderlogplus.app.data.VaultScanStateEntity
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.app.vault.VaultMediaAccess
import de.wanderlogplus.shared.sync.VaultScanStates

const val VAULT_ROUTE = "mehr/bilder-upload"

/**
 * Trigger and local status of the media upload. The work runs in a worker; this screen only reads the state row,
 * so it works without any server. [webUrl] points at the server's overview page (token included).
 */
@Composable
fun VaultScanScreen(
    state: VaultScanStateEntity?,
    found: Int,
    pending: Int,
    webUrl: String?,
    onStart: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val needed = VaultMediaAccess.needed(sdk)
    fun imagesGranted() = VaultMediaAccess.canReadImages(sdk, ::granted)
    fun videosGranted() = VaultMediaAccess.canReadVideos(sdk, ::granted)
    var canScan by remember { mutableStateOf(imagesGranted()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canScan = imagesGranted()
        asked = true
        if (canScan) onStart()
    }

    Column(
        modifier.fillMaxWidth().padding(16.dp).testTag("vault-scan-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SurfaceCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bilder-Upload", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Überträgt alle Bilder und Video-Vorschaubilder von diesem Gerät an deinen Server, sobald er erreichbar ist. Schon übertragene Dateien werden übersprungen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    state == null -> Text("Noch keine Übertragung gelaufen.", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        val label = when (state.state) {
                            VaultScanStates.RUNNING -> "Übertragung läuft …"
                            VaultScanStates.DONE -> "Übertragung fertig"
                            else -> "Übertragung fehlgeschlagen"
                        }
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        val total = state.total.coerceAtLeast(0)
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { (state.scanned.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${state.scanned} / $total Dateien",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("Erfasst: $found", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Davon noch nicht übertragen: $pending",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                val partialAccess = sdk >= 34 && canScan &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
                if (partialAccess) {
                    Text(
                        "Es ist nur Zugriff auf ausgewählte Fotos und Videos erlaubt – der Rest der Galerie wird nicht übertragen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { launcher.launch(needed) }) {
                        Text("Zugriff auf alle Medien erlauben")
                    }
                }
                val missingVideo = canScan && !videosGranted() && !partialAccess
                if (missingVideo) {
                    Text(
                        "Kein Zugriff auf Videos – ohne die Erlaubnis werden nur Fotos erfasst.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (asked && !canScan) {
                    Text(
                        "Ohne Zugriff auf die Bilder kann nichts übertragen werden. Bitte in den App-Einstellungen erlauben.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        val missing = VaultMediaAccess.missing(sdk, ::granted)
                        if (missing.isEmpty()) onStart() else launcher.launch(if (canScan) missing else needed)
                    },
                    modifier = Modifier.testTag("vault-scan-start"),
                ) {
                    Text(
                        when {
                            !canScan -> "Bilderzugriff erlauben & starten"
                            missingVideo -> "Videozugriff erlauben & starten"
                            else -> "Upload starten"
                        },
                    )
                }
                if (pending > 0) {
                    Button(onClick = onResume, modifier = Modifier.testTag("vault-upload-resume")) {
                        Text("Übertragung fortsetzen ($pending offen)")
                    }
                }
            }
        }
        if (webUrl != null) {
            SurfaceCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Web-Übersicht", style = MaterialTheme.typography.titleSmall)
                    Text(
                        webUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
