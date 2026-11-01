package de.wanderlogplus.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import de.wanderlogplus.app.AppContainer
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.data.ServerSettings
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as WanderlogPlusApp).container
        setContent {
            WanderlogPlusTheme {
                val hasServer by remember { container.settings.server.map { it != null } }.collectAsState(null)
                val snapshot by produceState<TripSnapshot?>(null) {
                    container.snapshots().snapshot.collect { value = it }
                }
                val server by remember { container.settings.server }.collectAsState(null)
                val nodeId by produceState<String?>(null) { value = withContext(Dispatchers.IO) { container.settings.nodeId } }
                val scope = rememberCoroutineScope()
                val status by container.syncRunner.status.collectAsState()
                LaunchedEffect(Unit) { container.syncRunner.refresh() }
                AppNavigation(
                    hasServer = hasServer,
                    snapshot = snapshot,
                    syncStatus = status,
                    onRefresh = container.syncScheduler::requestNow,
                    setupViewModelFactory = remember { setupFactory(container) },
                    tripViewModelFactory = remember { tripFactory(container) },
                    placesViewModelFactory = remember { placesFactory(container) },
                    moreViewModelFactory = remember { moreFactory(container) },
                    importViewModelFactory = remember { importFactory(container) },
                    settingsInfo = SettingsInfo(server?.url, server?.token, nodeId.orEmpty()),
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
            TripViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }) as T
    }

    private fun placesFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlacesViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }) as T
    }

    private fun moreFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MoreViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }) as T
    }

    private fun importFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ImportViewModel(
            api = { container.settings.server.first()?.let { SyncApi(container.http, it.url, it.token) } },
            onImported = container.syncScheduler::requestNow,
        ) as T
    }

    private fun setupFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val tester = SetupViewModel.connectionTester(container.http)
            return SetupViewModel(tester) { url, token ->
                container.settings.saveServer(ServerSettings(url, token))
                container.syncScheduler.schedulePeriodic()
                container.syncScheduler.requestNow()
            } as T
        }
    }
}
