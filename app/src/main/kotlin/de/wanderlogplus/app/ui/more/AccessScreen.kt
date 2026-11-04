package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.AccessToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val ACCESS_ROUTE = "mehr/zugaenge"

data class AccessState(
    val hasAdminToken: Boolean = false,
    val tokens: List<AccessToken> = emptyList(),
    val loading: Boolean = false,
    /** Fixed German text; null when fine. */
    val message: String? = null,
    /** Just created: shown with its QR code. */
    val created: AccessToken? = null,
)

fun accessError(e: ApiException): String = when {
    e.kind == ApiErrorKind.NOT_CONFIGURED -> "Auf dem Server ist kein Admin-Token eingetragen (WLP_ADMIN_TOKEN)."
    e.kind == ApiErrorKind.UNAUTHORIZED -> "Der Admin-Token stimmt nicht."
    e.kind == ApiErrorKind.NETWORK -> "Server nicht erreichbar."
    e.code == "token_name_taken" -> "Diesen Namen gibt es schon."
    e.code == "token_in_env" -> "Dieser Zugang steht in der .env des Servers (WLP_TOKENS) und lässt sich nur dort entfernen."
    e.code == "bad_request" -> "Name: 1–40 Zeichen, Buchstaben, Ziffern, Leerzeichen, Punkt, Unterstrich, Bindestrich."
    else -> "Die Verwaltung antwortet gerade nicht."
}

/**
 * "Zugänge verwalten": with the admin token (stored per profile, never used for sync) lists, creates and revokes
 * the server's access tokens. [adminApi] builds the API for the current server with a given admin token.
 */
class AccessViewModel(
    private val adminApi: suspend (adminToken: String) -> SyncApi?,
    private val loadToken: suspend () -> String?,
    private val saveToken: suspend (String?) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(AccessState())
    val state: StateFlow<AccessState> = _state

    init {
        viewModelScope.launch {
            val t = loadToken()
            _state.update { it.copy(hasAdminToken = t != null) }
            if (t != null) refresh()
        }
    }

    fun setAdminToken(token: String) {
        val t = token.trim().ifBlank { return }
        viewModelScope.launch {
            saveToken(t)
            _state.update { it.copy(hasAdminToken = true, message = null) }
            refresh()
        }
    }

    fun forgetAdminToken() {
        viewModelScope.launch {
            saveToken(null)
            _state.value = AccessState()
        }
    }

    fun refresh() = run { a -> _state.update { it.copy(tokens = a.adminTokens()) } }

    fun create(name: String) = run { a ->
        val t = a.createToken(name.trim())
        _state.update { it.copy(created = t, tokens = a.adminTokens()) }
    }

    fun delete(name: String) = run { a ->
        a.deleteToken(name)
        _state.update { it.copy(tokens = a.adminTokens()) }
    }

    fun dismissCreated() = _state.update { it.copy(created = null) }

    private fun run(block: suspend (SyncApi) -> Unit) {
        viewModelScope.launch {
            val token = loadToken() ?: return@launch
            val a = adminApi(token) ?: run { _state.update { it.copy(message = "Kein Server eingerichtet.") }; return@launch }
            _state.update { it.copy(loading = true, message = null) }
            try {
                block(a)
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, message = accessError(e)) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = "Die Verwaltung antwortet gerade nicht.") }
            }
        }
    }
}

/**
 * The screen: admin token entry, then every access token with a QR code to hand it out and (for tokens created in
 * the app) a revoke button, and "Neuen Zugang anlegen". Tags: `access-admin-token`, `access-admin-save`,
 * `access-token-<name>`, `access-qr-<name>`, `access-delete-<name>`, `access-new-name`, `access-create`.
 */
@Composable
fun AccessScreen(
    state: AccessState,
    serverUrl: String?,
    onSaveAdminToken: (String) -> Unit,
    onForgetAdminToken: () -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissCreated: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var adminInput by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf("") }
    var qrFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
    // A QR code with just the admin token (e.g. shown on the PC) saves typing it.
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        r.contents?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("wlp://") }?.let(onSaveAdminToken)
    }

    Column(modifier.fillMaxSize().imePadding().testTag("access-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text("Zugänge verwalten", style = MaterialTheme.typography.titleLarge)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("access-message")) }
            if (!state.hasAdminToken) {
                Text(
                    "Mit dem Admin-Token (WLP_ADMIN_TOKEN aus der .env des Servers) siehst du alle Zugänge, legst neue an und verteilst sie per QR-Code. Er bleibt auf diesem Gerät und wird nie zum Synchronisieren benutzt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    adminInput, { adminInput = it },
                    label = { Text("Admin-Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("access-admin-token"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSaveAdminToken(adminInput); adminInput = "" }, enabled = adminInput.isNotBlank(), modifier = Modifier.testTag("access-admin-save")) {
                        Text("Speichern")
                    }
                    OutlinedButton(
                        onClick = {
                            scanner.launch(
                                com.journeyapps.barcodescanner.ScanOptions()
                                    .setDesiredBarcodeFormats(com.google.zxing.BarcodeFormat.QR_CODE.name)
                                    .setBeepEnabled(false)
                                    .setPrompt("QR-Code mit dem Admin-Token scannen"),
                            )
                        },
                        modifier = Modifier.testTag("access-admin-scan"),
                    ) { Text("QR-Code scannen") }
                }
                return@Column
            }

            SectionLabel("Zugänge")
            SurfaceCard {
                Column {
                    state.tokens.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("access-token-${t.name}")) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (t.source == "env") "aus WLP_TOKENS (.env)" else "in der App angelegt" + if (t.createdAt > 0) " am " + dateFormat.format(Date(t.createdAt)) else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (serverUrl != null) TextButton(onClick = { qrFor = t.name }, modifier = Modifier.testTag("access-qr-${t.name}")) { Text("QR-Code") }
                                if (t.source != "env") TextButton(onClick = { confirmDelete = t.name }, modifier = Modifier.testTag("access-delete-${t.name}")) { Text("Widerrufen") }
                            }
                        }
                    }
                }
            }

            SectionLabel("Neuer Zugang")
            OutlinedTextField(newName, { newName = it }, label = { Text("Name (erscheint als Autor der Änderungen)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("access-new-name"))
            Button(onClick = { onCreate(newName); newName = "" }, enabled = newName.isNotBlank() && !state.loading, modifier = Modifier.testTag("access-create")) { Text("Zugang anlegen") }

            OutlinedButton(onClick = onForgetAdminToken, modifier = Modifier.padding(top = 16.dp).testTag("access-forget")) { Text("Admin-Token auf diesem Gerät vergessen") }
        }
    }

    val qrToken = state.created ?: qrFor?.let { n -> state.tokens.firstOrNull { it.name == n } }
    if (qrToken != null && serverUrl != null) {
        SetupQrDialog(ServerSettings(serverUrl, qrToken.token), debugMode = false, onDismiss = { qrFor = null; onDismissCreated() }, title = "Zugang für ${qrToken.name}")
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Zugang „$name“ widerrufen?") },
            text = { Text("Geräte mit diesem Token können danach nicht mehr synchronisieren.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onDelete(name) }, modifier = Modifier.testTag("access-delete-confirm")) { Text("Widerrufen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } },
        )
    }
}
