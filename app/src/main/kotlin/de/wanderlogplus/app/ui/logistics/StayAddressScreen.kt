package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.client.TripSnapshot

/** Route of [StayAddressScreen] (from the stay widget's „Adresse zeigen“). */
const val STAY_ADDRESS_ROUTE = "stay-address/{stayId}"

fun stayAddressRoute(stayId: String) = "stay-address/${android.net.Uri.encode(stayId)}"

/** The stay's name and address in large type to show a taxi driver; the screen stays on while it is shown. */
@Composable
fun StayAddressScreen(snapshot: TripSnapshot?, stayId: String, onBack: () -> Unit) {
    val stay = snapshot?.stays?.firstOrNull { it.id == stayId }
    val place = stay?.let { snapshot.places[it.placeId] }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("stay-address-screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("stay-address-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        if (place == null) {
            Text("Unterkunft nicht gefunden", style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(place.name.ifBlank { "Unterkunft" }, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                place.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
                    ?: Text("Keine Adresse hinterlegt", style = MaterialTheme.typography.titleMedium)
                place.phone?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
