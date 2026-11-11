package de.wanderlogplus.app.ui.guides

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.openGuideLink
import de.wanderlogplus.shared.model.GuideLink
import de.wanderlogplus.shared.model.GuideLinkKind

/** The apps and pages of guides as chips (app or globe icon); a tap opens them. Nothing for an empty list. Tag `guide-links`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideLinkChips(links: List<GuideLink>, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    val context = LocalContext.current
    FlowRow(modifier.testTag("guide-links"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { l ->
            AssistChip(
                onClick = { if (!openGuideLink(context, l)) Toast.makeText(context, "Lässt sich nicht öffnen", Toast.LENGTH_SHORT).show() },
                label = { Text(l.name) },
                leadingIcon = { Icon(if (l.kind == GuideLinkKind.APP) Icons.Filled.Apps else Icons.Filled.Language, contentDescription = null) },
                modifier = Modifier.padding(vertical = 2.dp).testTag("guide-link"),
            )
        }
    }
}
