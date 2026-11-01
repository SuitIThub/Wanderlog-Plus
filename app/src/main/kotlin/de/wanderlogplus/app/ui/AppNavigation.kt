package de.wanderlogplus.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import de.wanderlogplus.app.sync.SyncStatus
import de.wanderlogplus.app.ui.setup.SetupScreen
import de.wanderlogplus.app.ui.setup.SetupViewModel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.app.ui.trip.DAY_ROUTE
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.app.ui.trip.TripScreen
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.app.ui.trip.dayRoute
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HEUTE("heute", "Heute", Icons.Filled.Today),
    REISE("reise", "Reise", Icons.Filled.CalendarMonth),
    ORTE("orte", "Orte", Icons.Filled.Place),
    LOGISTIK("logistik", "Logistik", Icons.Filled.Train),
    MEHR("mehr", "Mehr", Icons.Filled.MoreHoriz),
}

/** Route of the server setup screen; "Mehr" (Task 9) navigates here to change the server. */
const val SETUP_ROUTE = "setup"

private val EMPTY_AWARE_TABS = setOf(Tab.HEUTE, Tab.REISE, Tab.ORTE, Tab.LOGISTIK)

/**
 * [hasServer] is null while the settings are loading (nothing is shown yet), false when Setup must be shown first.
 * [setupViewModelFactory] creates the [SetupViewModel] when the setup route is shown.
 */
@Composable
fun AppNavigation(
    hasServer: Boolean? = true,
    snapshot: TripSnapshot? = null,
    syncStatus: SyncStatus = SyncStatus(),
    onRefresh: () -> Unit = {},
    setupViewModelFactory: ViewModelProvider.Factory? = null,
    tripViewModelFactory: ViewModelProvider.Factory? = null,
) {
    if (hasServer == null) return
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current != SETUP_ROUTE) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route || (tab == Tab.REISE && current == DAY_ROUTE),
                        onClick = {
                            if (tab == Tab.REISE && current == DAY_ROUTE) {
                                nav.popBackStack(Tab.REISE.route, inclusive = false)
                                return@NavigationBarItem
                            }
                            nav.navigate(tab.route) {
                                popUpTo(Tab.HEUTE.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = if (hasServer) Tab.HEUTE.route else SETUP_ROUTE,
            modifier = Modifier.padding(padding),
        ) {
            composable(SETUP_ROUTE) {
                val vm: SetupViewModel = viewModel(factory = requireNotNull(setupViewModelFactory))
                SetupScreen(vm, onSaved = {
                    nav.navigate(Tab.HEUTE.route) { popUpTo(0) { inclusive = true } }
                })
            }
            if (tripViewModelFactory != null) {
                composable(Tab.REISE.route) {
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    TripErrors(vm, snackbar)
                    SyncedScreen(syncStatus, onRefresh, scrollable = snap?.isEmpty != false) {
                        val s = snap
                        when {
                            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            s.isEmpty -> EmptyState(onSyncNow = onRefresh)
                            else -> TripScreen(s, onOpenDay = { nav.navigate(dayRoute(it)) })
                        }
                    }
                }
                composable(DAY_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.getBackStackEntry(Tab.REISE.route))
                    val snap by vm.snapshot.collectAsState()
                    TripErrors(vm, snackbar)
                    val date = entry.arguments?.getString("date").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let {
                            DayDetailScreen(
                                snapshot = it,
                                date = date,
                                onBack = { nav.popBackStack() },
                                onAddSuggestion = vm::addSuggestion,
                                onMove = vm::move,
                                onRemove = vm::remove,
                            )
                        }
                    }
                }
            }
            Tab.entries.filter { it != Tab.REISE || tripViewModelFactory == null }.forEach { tab ->
                composable(tab.route) {
                    SyncedScreen(syncStatus, onRefresh) {
                        if (tab in EMPTY_AWARE_TABS && snapshot?.isEmpty == true) {
                            EmptyState(onSyncNow = onRefresh)
                        } else {
                            PlaceholderScreen(tab.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripErrors(vm: TripViewModel, snackbar: SnackbarHostState) {
    val error by vm.error.collectAsState()
    LaunchedEffect(error) {
        if (error != null) {
            vm.consumeError()
            snackbar.showSnackbar("Änderung fehlgeschlagen")
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(Modifier.fillMaxSize().testTag("screen-$title"), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
