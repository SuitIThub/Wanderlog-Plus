package de.wanderlogplus.app.ui

import android.content.Intent
import android.os.Bundle
import de.wanderlogplus.app.ui.setup.SetupLink
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.AppContainer
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.data.TripZone
import de.wanderlogplus.app.data.now
import de.wanderlogplus.app.ui.attachments.AttachmentsViewModel
import de.wanderlogplus.app.ui.setup.SetupViewModel
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.client.TripSnapshot
import androidx.compose.runtime.rememberCoroutineScope
import de.wanderlogplus.app.ui.more.ImportViewModel
import de.wanderlogplus.app.ui.more.MoreViewModel
import de.wanderlogplus.app.ui.more.SettingsInfo
import de.wanderlogplus.client.SyncApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Setup link from the deep link; prefill only (never saved automatically). Held in memory only, never logged. */
    private val pendingSetup = MutableStateFlow<ServerSettings?>(null)

    // launchMode="singleTop": a new wlp:// link reaches the running activity via onNewIntent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupIntent(intent)
    }

    private fun handleSetupIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        SetupLink.parse(raw)?.let { pendingSetup.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as WanderlogPlusApp).container
        if (savedInstanceState == null) handleSetupIntent(intent)
        setContent {
            WanderlogPlusTheme {
                val hasServer by remember { container.settings.server.map { it != null } }.collectAsState(null)
                var loadFailed by remember { mutableStateOf(false) }
                val snapshot by produceState<TripSnapshot?>(null) {
                    try {
                        container.snapshots().snapshot.collect { value = it }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        loadFailed = true // no details: they could carry data
                    }
                }
                val server by remember { container.settings.server }.collectAsState(null)
                val serverRef = rememberUpdatedState(server)
                val nodeId by produceState<String?>(null) {
                    value = try { withContext(Dispatchers.IO) { container.settings.nodeId } } catch (e: CancellationException) { throw e } catch (e: Exception) { loadFailed = true; null }
                }
                val tripZone by remember { container.settings.tripZone }.collectAsState(TripZone.JAPAN)
                val scope = rememberCoroutineScope()
                val status by container.syncRunner.status.collectAsState()
                LaunchedEffect(Unit) { container.syncRunner.refresh() }
                if (loadFailed) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Lokale Daten konnten nicht geladen werden", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    return@WanderlogPlusTheme
                }
                val pending by pendingSetup.collectAsState()
                AppNavigation(
                    pendingSetup = pending,
                    onPendingSetupConsumed = { pendingSetup.value = null },
                    hasServer = hasServer,
                    snapshot = snapshot,
                    syncStatus = status,
                    onRefresh = container.syncScheduler::requestNow,
                    setupViewModelFactory = remember { setupFactory(container) { serverRef.value } },
                    tripViewModelFactory = remember { tripFactory(container) },
                    placesViewModelFactory = remember { placesFactory(container) },
                    moreViewModelFactory = remember { moreFactory(container) },
                    importViewModelFactory = remember { importFactory(container) },
                    settingsInfo = SettingsInfo(server?.url, server?.token, nodeId.orEmpty()),
                    tripZone = tripZone,
                    onTripZone = { z -> scope.launch { container.settings.setTripZone(z) } },
                    clock = { tripZone.now() },
                    attachmentsViewModelFactory = remember { attachmentsFactory(container) },
                    attachmentFiles = container.attachmentFiles,
                    onResetSync = {
                        scope.launch {
                            container.syncRunner.resetCursor()
                            container.syncScheduler.requestNow()
                        }
                    },
                )
            }
        }
    }

    private fun tripFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TripViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, container.scope) as T
    }

    private fun placesFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlacesViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, container.scope) as T
    }

    private fun moreFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MoreViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, container.scope) as T
    }

    private fun attachmentsFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AttachmentsViewModel(
            edit = { block -> container.edit(block) },
            addAttachment = { type, id, input, name, mime -> container.addAttachment(type, id, input, name, mime) },
            ensureDay = container::ensureDay,
            writeScope = container.scope,
        ) as T
    }

    private fun importFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(
            api = { container.settings.server.first()?.let { SyncApi(container.http, it.url, it.token) } },
            onImported = container.syncScheduler::requestNow,
        ) as T
    }

    private fun setupFactory(container: AppContainer, current: () -> ServerSettings?) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val tester = SetupViewModel.connectionTester(container.http)
            val cur = current()
            return SetupViewModel(tester, cur?.url.orEmpty(), cur?.token.orEmpty()) { url, token ->
                container.settings.saveServer(ServerSettings(url, token))
                container.syncScheduler.schedulePeriodic()
                container.syncScheduler.requestNow()
            } as T
        }
    }
}
