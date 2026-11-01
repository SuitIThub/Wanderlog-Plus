package de.wanderlogplus.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.sync.SyncStatus

@Composable
fun SyncStatusBar(status: SyncStatus, now: Long = System.currentTimeMillis(), modifier: Modifier = Modifier) {
    val (text, isError) = when {
        status.running -> "Synchronisiere …" to false
        status.lastError != null -> status.lastError to true
        status.pending > 0 -> (if (status.pending == 1) "1 Änderung wartet" else "${status.pending} Änderungen warten") to false
        status.lastSyncAt != null -> "Zuletzt synchronisiert ${relativeAgo(status.lastSyncAt, now)}" to false
        else -> "Noch nicht synchronisiert" to false
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Reusable empty state with a sync button. */
@Composable
fun EmptyState(onSyncNow: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Noch keine Daten – jetzt synchronisieren", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onSyncNow, modifier = Modifier.padding(top = 16.dp)) { Text("Jetzt synchronisieren") }
        }
    }
}

/** Status bar on top, pull-to-refresh around the (scrollable) content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedScreen(
    status: SyncStatus,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        SyncStatusBar(status)
        PullToRefreshBox(isRefreshing = status.running, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            if (scrollable) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
            } else {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}
