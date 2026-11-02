package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag

/** From this window width on, screens use the extra room (list and detail side by side, map beside its list). */
const val WIDE_LAYOUT_MIN_DP = 600

/**
 * True when the window is wide enough for two panes: an unfolded foldable (e.g. Galaxy Z Fold inner display), a tablet
 * or landscape. Follows configuration changes, so folding and unfolding switch the layout live.
 */
@Composable
fun isWideLayout(): Boolean = LocalConfiguration.current.screenWidthDp >= WIDE_LAYOUT_MIN_DP

/** List on the left ([listWeight] of the width), detail on the right, separated by a thin line. Tag `two-pane`. */
@Composable
fun TwoPane(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    listWeight: Float = 0.4f,
) {
    Row(modifier.fillMaxSize().testTag("two-pane")) {
        Box(Modifier.weight(listWeight).fillMaxHeight().testTag("pane-list")) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f - listWeight).fillMaxHeight().testTag("pane-detail")) { detail() }
    }
}
