package de.wanderlogplus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.ExternalLinkProvider
import de.wanderlogplus.app.links.GoogleExternalLinks
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkButtons(links: List<ExternalLink>, onOpen: (ExternalLink) -> Unit, modifier: Modifier = Modifier) {
    if (links.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { l ->
            OutlinedButton(onClick = { onOpen(l) }, modifier = Modifier.testTag("link-${l.kind}")) { Text(l.label) }
        }
    }
}

/** Navigation and transit only, as used for stays. */
fun stayLinks(place: Place, provider: ExternalLinkProvider = GoogleExternalLinks): List<ExternalLink> =
    provider.linksFor(place, null).filter { it.kind == LinkKind.NAVIGATE || it.kind == LinkKind.TRANSIT }
