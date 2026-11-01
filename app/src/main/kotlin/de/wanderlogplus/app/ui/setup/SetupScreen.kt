package de.wanderlogplus.app.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.testTag
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.BarcodeFormat
import androidx.activity.compose.rememberLauncherForActivityResult

@Composable
fun SetupScreen(vm: SetupViewModel, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsState()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { vm.onScanned(it.contents) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        Text("Server einrichten", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(
            onClick = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.name)
                        .setBeepEnabled(false)
                        .setPrompt("QR-Code der Einrichtung scannen"),
                )
            },
            modifier = Modifier.testTag("setup-scan"),
        ) { Text("QR-Code scannen") }
        s.scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = s.url,
            onValueChange = vm::setUrl,
            label = { Text("Server-Adresse") },
            singleLine = true,
            isError = s.urlError != null,
            supportingText = s.urlError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (s.insecure) {
            Text(
                "Unverschlüsselte Verbindung über das Internet – Token und Buchungsdaten sind lesbar.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedTextField(
            value = s.token,
            onValueChange = vm::setToken,
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = vm::test, enabled = !s.testing) {
            Text(if (s.testing) "Teste …" else "Verbindung testen")
        }
        s.connectedAs?.let { Text("Verbunden als $it", color = MaterialTheme.colorScheme.primary) }
        s.testError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save(onSaved) }, enabled = s.canSave) { Text("Speichern") }
    }
}
