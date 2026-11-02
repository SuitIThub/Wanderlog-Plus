package de.wanderlogplus.app.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.IconTile
import de.wanderlogplus.app.ui.design.SurfaceCard

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
    Column(modifier.fillMaxWidth().padding(16.dp).testTag("more-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SurfaceCard {
            MoreRow("Notizen", Icons.AutoMirrored.Filled.Notes) { onOpen(NOTES_ROUTE) }
            HorizontalDivider(Modifier.padding(start = 68.dp))
            MoreRow("Import", Icons.Filled.CloudDownload) { onOpen(IMPORT_ROUTE) }
            HorizontalDivider(Modifier.padding(start = 68.dp))
            MoreRow("Reels auswerten (KI)", Icons.Filled.Movie) { onOpen(de.wanderlogplus.app.ui.reels.REELS_ROUTE) }
            HorizontalDivider(Modifier.padding(start = 68.dp))
            MoreRow("KI-Kriterien", Icons.Filled.Tune) { onOpen(AI_SETTINGS_ROUTE) }
            HorizontalDivider(Modifier.padding(start = 68.dp))
            MoreRow("Konflikte", Icons.Filled.SyncProblem, badge = if (conflictCount > 0) BadgeSpec("$conflictCount", BadgeTone.Warning) else null) {
                onOpen(CONFLICTS_ROUTE)
            }
        }
        SurfaceCard {
            MoreRow("Einstellungen", Icons.Filled.Settings) { onOpen(SETTINGS_ROUTE) }
        }
    }
}

@Composable
private fun MoreRow(label: String, icon: ImageVector, badge: BadgeSpec? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { IconTile(icon) },
        trailingContent = badge?.let { { Badge(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
