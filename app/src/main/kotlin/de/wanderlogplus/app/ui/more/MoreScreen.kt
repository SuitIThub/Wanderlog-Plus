package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val NOTES_ROUTE = "mehr/notizen"
const val IMPORT_ROUTE = "mehr/import"
const val CONFLICTS_ROUTE = "mehr/konflikte"
const val SETTINGS_ROUTE = "mehr/einstellungen"

@Composable
fun MoreScreen(
    conflictCount: Int,
    onOpen: (route: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("more-list")) {
        listOf(
            "Notizen" to NOTES_ROUTE,
            "Import" to IMPORT_ROUTE,
            "Konflikte ($conflictCount)" to CONFLICTS_ROUTE,
            "Einstellungen" to SETTINGS_ROUTE,
        ).forEach { (label, route) ->
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(route) }.padding(16.dp),
            )
            HorizontalDivider()
        }
    }
}
