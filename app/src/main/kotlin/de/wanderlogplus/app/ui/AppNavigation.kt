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
import de.wanderlogplus.app.ui.places.PLACE_ROUTE
import de.wanderlogplus.app.ui.places.PlaceDetailScreen
import de.wanderlogplus.app.ui.places.PlacesScreen
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.places.REGION_ROUTE
import de.wanderlogplus.app.ui.places.RegionPlacesScreen
import de.wanderlogplus.app.ui.places.placeRoute
import de.wanderlogplus.app.ui.places.regionRoute
import de.wanderlogplus.app.ui.logistics.LogisticsScreen
import de.wanderlogplus.app.ui.more.CONFLICTS_ROUTE
import de.wanderlogplus.app.ui.more.ConflictsScreen
import de.wanderlogplus.app.ui.more.IMPORT_ROUTE
import de.wanderlogplus.app.ui.more.ImportScreen
import de.wanderlogplus.app.ui.more.ImportViewModel
import de.wanderlogplus.app.ui.more.MoreScreen
import de.wanderlogplus.app.ui.more.MoreViewModel
import de.wanderlogplus.app.ui.more.NOTES_ROUTE
import de.wanderlogplus.app.ui.more.NOTE_ROUTE
import de.wanderlogplus.app.ui.more.NoteDetailScreen
import de.wanderlogplus.app.ui.more.NotesScreen
import de.wanderlogplus.app.ui.more.SETTINGS_ROUTE
import de.wanderlogplus.app.ui.more.SettingsInfo
import de.wanderlogplus.app.ui.more.SettingsScreen
import de.wanderlogplus.app.ui.more.noteRoute
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.app.ui.today.rememberMinuteNow
import de.wanderlogplus.app.ui.trip.DAY_ROUTE
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.app.ui.trip.TripScreen
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.app.ui.trip.dayRoute
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
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
    ;

    /** Routes nested below this tab's root (the tab stays highlighted there). */
    val nestedRoutes: Set<String>
        get() = when (this) {
            REISE -> setOf(DAY_ROUTE)
            ORTE -> setOf(REGION_ROUTE, PLACE_ROUTE)
            MEHR -> setOf(NOTES_ROUTE, NOTE_ROUTE, IMPORT_ROUTE, CONFLICTS_ROUTE, SETTINGS_ROUTE)
            else -> emptySet()
        }
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
    placesViewModelFactory: ViewModelProvider.Factory? = null,
    /** Logistik and Mehr (notes, conflicts); without it these tabs show placeholders. */
    moreViewModelFactory: ViewModelProvider.Factory? = null,
    importViewModelFactory: ViewModelProvider.Factory? = null,
    settingsInfo: SettingsInfo = SettingsInfo(null, null, ""),
    onResetSync: () -> Unit = {},
    /** Device-local "now" for the Heute tab; injectable for tests. */
    clock: () -> LocalDateTime = { LocalDateTime.now() },
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
                        selected = current == tab.route || current in tab.nestedRoutes,
                        onClick = {
                            if (current in tab.nestedRoutes && nav.popBackStack(tab.route, inclusive = false)) {
                                return@NavigationBarItem
                            }
                            nav.navigateToTab(tab)
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
                composable(Tab.HEUTE.route) {
                    val s = snapshot
                    val now = rememberMinuteNow(clock)
                    SyncedScreen(syncStatus, onRefresh, scrollable = s?.isEmpty != false) {
                        if (s == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            TodayScreen(
                                s, now,
                                onOpenDay = { nav.navigate(dayRoute(it)) },
                                onOpenTrip = { nav.navigateToTab(Tab.REISE) },
                                onSyncNow = onRefresh,
                            )
                        }
                    }
                }
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
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
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
            if (placesViewModelFactory != null) {
                composable(Tab.ORTE.route) {
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    ErrorSnack(vm.error, vm::consumeError, snackbar)
                    SyncedScreen(syncStatus, onRefresh, scrollable = snap?.isEmpty != false) {
                        val s = snap
                        when {
                            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            s.isEmpty -> EmptyState(onSyncNow = onRefresh)
                            else -> PlacesScreen(s, onOpenRegion = { nav.navigate(regionRoute(it)) })
                        }
                    }
                }
                composable(REGION_ROUTE) { entry ->
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.ORTE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val key = entry.arguments?.getString("regionKey").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let {
                            RegionPlacesScreen(it, key, onBack = { nav.popBackStack() }, onOpenPlace = { id -> nav.navigate(placeRoute(id)) })
                        }
                    }
                }
                composable(PLACE_ROUTE) { entry ->
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.ORTE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    ErrorSnack(vm.error, vm::consumeError, snackbar)
                    val id = entry.arguments?.getString("savedPlaceId").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let {
                            PlaceDetailScreen(it, id, onBack = { nav.popBackStack() }, onSaveNote = vm::setNote, onSetStatus = vm::setStatus)
                        }
                    }
                }
            }
            if (moreViewModelFactory != null) {
                composable(Tab.LOGISTIK.route) {
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    SyncedScreen(syncStatus, onRefresh, scrollable = snap?.isEmpty != false) {
                        val s = snap
                        when {
                            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            s.isEmpty -> EmptyState(onSyncNow = onRefresh)
                            else -> LogisticsScreen(s)
                        }
                    }
                }
                composable(Tab.MEHR.route) {
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    SyncedScreen(syncStatus, onRefresh) {
                        MoreScreen(snap?.conflicts?.size ?: 0, onOpen = { nav.navigate(it) })
                    }
                }
                composable(NOTES_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.MEHR.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s -> NotesScreen(s, onOpen = { id -> nav.navigate(noteRoute(id)) }) }
                    }
                }
                composable(NOTE_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.MEHR.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val id = entry.arguments?.getString("noteId").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s -> NoteDetailScreen(s, id, onBack = { nav.popBackStack() }) }
                    }
                }
                composable(CONFLICTS_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.MEHR.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    ErrorSnack(vm.error, vm::consumeError, snackbar)
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s -> ConflictsScreen(s, onKeepApp = vm::keepApp, onTakeWanderlog = vm::takeWanderlog) }
                    }
                }
                composable(SETTINGS_ROUTE) {
                    SyncedScreen(syncStatus, onRefresh) {
                        SettingsScreen(
                            info = settingsInfo,
                            status = syncStatus,
                            onChangeServer = { nav.navigate(SETUP_ROUTE) },
                            onSyncNow = onRefresh,
                            onResetSync = onResetSync,
                        )
                    }
                }
            }
            if (importViewModelFactory != null) {
                composable(IMPORT_ROUTE) {
                    val vm: ImportViewModel = viewModel(factory = importViewModelFactory)
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) { ImportScreen(vm) }
                }
            }
            Tab.entries.filter {
                !(it == Tab.REISE && tripViewModelFactory != null) &&
                    !(it == Tab.HEUTE && tripViewModelFactory != null) &&
                    !(it == Tab.ORTE && placesViewModelFactory != null) &&
                    !((it == Tab.LOGISTIK || it == Tab.MEHR) && moreViewModelFactory != null)
            }.forEach { tab ->
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
private fun TripErrors(vm: TripViewModel, snackbar: SnackbarHostState) = ErrorSnack(vm.error, vm::consumeError, snackbar)

@Composable
private fun ErrorSnack(errorFlow: StateFlow<String?>, consume: () -> Unit, snackbar: SnackbarHostState) {
    val error by errorFlow.collectAsState()
    LaunchedEffect(error) {
        if (error != null) {
            consume()
            snackbar.showSnackbar("Änderung fehlgeschlagen")
        }
    }
}

private fun NavController.entryOrNull(route: String) =
    try { getBackStackEntry(route) } catch (_: IllegalArgumentException) { null }

private fun NavController.navigateToTab(tab: Tab) = navigate(tab.route) {
    popUpTo(Tab.HEUTE.route) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(Modifier.fillMaxSize().testTag("screen-$title"), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
