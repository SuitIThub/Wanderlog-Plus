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
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.sync.SyncStatus
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.openExternalLink
import de.wanderlogplus.app.ui.attachments.MSG_NO_APP
import kotlinx.coroutines.launch
import de.wanderlogplus.app.ui.attachments.ATTACHMENT_ROUTE
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentViewerScreen
import de.wanderlogplus.app.ui.attachments.AttachmentsViewModel
import de.wanderlogplus.app.ui.attachments.attachmentRoute
import de.wanderlogplus.app.ui.attachments.rememberAttachmentActions
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
import de.wanderlogplus.app.ui.logistics.LOGISTICS_DETAIL_ROUTE
import de.wanderlogplus.app.ui.logistics.LOGISTICS_EDIT_ROUTE
import de.wanderlogplus.app.ui.logistics.LogisticsEditScreen
import de.wanderlogplus.app.ui.logistics.PlaceSearch
import de.wanderlogplus.app.ui.logistics.logisticsEditRoute
import de.wanderlogplus.app.ui.logistics.LogisticsDetailScreen
import de.wanderlogplus.app.ui.logistics.LogisticsScreen
import de.wanderlogplus.app.ui.logistics.logisticsDetailRoute
import de.wanderlogplus.app.ui.logistics.logisticsTypeOf
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
import de.wanderlogplus.app.data.TripZone
import de.wanderlogplus.app.ui.more.SettingsScreen
import de.wanderlogplus.app.ui.more.noteRoute
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.app.ui.today.rememberMinuteNow
import de.wanderlogplus.app.ui.trip.DAY_ROUTE
import de.wanderlogplus.app.ui.trip.DAY_SEARCH_ROUTE
import de.wanderlogplus.app.ui.trip.PlaceSearchScreen
import de.wanderlogplus.app.ui.trip.daySearchRoute
import de.wanderlogplus.app.ui.trip.rememberPlaceSearchModel
import de.wanderlogplus.app.ui.trip.searchStart
import de.wanderlogplus.client.GeoSearch
import de.wanderlogplus.client.ItemPosition
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.app.ui.trip.TripScreen
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.app.ui.trip.dayRoute
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import de.wanderlogplus.app.data.ServerSettings
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
            REISE -> setOf(DAY_ROUTE, DAY_SEARCH_ROUTE)
            ORTE -> setOf(REGION_ROUTE, PLACE_ROUTE)
            LOGISTIK -> setOf(LOGISTICS_DETAIL_ROUTE, LOGISTICS_EDIT_ROUTE)
            MEHR -> setOf(NOTES_ROUTE, NOTE_ROUTE, IMPORT_ROUTE, CONFLICTS_ROUTE, SETTINGS_ROUTE)
            else -> emptySet()
        }
}

/** Route of the server setup screen; "Mehr" (Task 9) navigates here to change the server. */
const val SETUP_ROUTE = "setup"

/** Snackbar after "Kartenbilder neu laden". */
const val MSG_RELOAD_MAPS = "Kartenbilder werden neu geladen"

private val EMPTY_AWARE_TABS = setOf(Tab.HEUTE, Tab.REISE, Tab.ORTE, Tab.LOGISTIK)

/**
 * [hasServer] is null while the settings are loading (nothing is shown yet), false when Setup must be shown first.
 * [setupViewModelFactory] creates the [SetupViewModel] when the setup route is shown.
 */
@Composable
fun AppNavigation(
    hasServer: Boolean? = true,
    /** Place search for stopovers (OpenStreetMap); null hides the search buttons. */
    placeSearch: PlaceSearch? = null,
    /** Place search with map for the day program ("Ort suchen"); null hides it. */
    geoSearch: GeoSearch? = null,
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
    tripZone: TripZone = TripZone.JAPAN,
    onTripZone: (TripZone) -> Unit = {},
    remindersEnabled: Boolean = true,
    onRemindersEnabled: (Boolean) -> Unit = {},
    /** Trip-zone "now" for the Heute tab; injectable for tests. */
    clock: () -> LocalDateTime = { LocalDateTime.now() },
    /** Attachments (add, open, delete, viewer); without both, no attachment UI is shown. */
    attachmentsViewModelFactory: ViewModelProvider.Factory? = null,
    attachmentFiles: AttachmentFiles? = null,
    /** Server settings from a setup deep link; shown on the setup screen (prefill only), then [onPendingSetupConsumed]. */
    pendingSetup: ServerSettings? = null,
    onPendingSetupConsumed: () -> Unit = {},
    /** Deletes the cached route map pictures ("Kartenbilder neu laden" in Einstellungen). */
    onReloadMapImages: suspend () -> Unit = {},
) {
    if (hasServer == null) return
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val graphReady = backStack != null
    LaunchedEffect(pendingSetup, graphReady) {
        // On a cold start the NavHost below has not set its graph yet; navigating then would crash.
        if (!graphReady) return@LaunchedEffect
        if (pendingSetup != null && nav.currentDestination?.route != SETUP_ROUTE) {
            nav.navigate(SETUP_ROUTE) { launchSingleTop = true }
        }
    }

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
                        modifier = Modifier.testTag("nav-${tab.route}"),
                    )
                }
            }
        },
    ) { padding ->
        val linkContext = LocalContext.current
        val uiScope = rememberCoroutineScope()
        val onOpenLink: (ExternalLink) -> Unit = { link ->
            if (!openExternalLink(linkContext, link)) uiScope.launch { snackbar.showSnackbar(MSG_NO_APP) }
        }
        val attachmentActions: AttachmentActions? =
            if (attachmentsViewModelFactory != null && attachmentFiles != null) {
                val avm: AttachmentsViewModel = viewModel(factory = attachmentsViewModelFactory)
                rememberAttachmentActions(avm, attachmentFiles, snackbar) { a -> nav.navigate(attachmentRoute(a.id)) }
            } else {
                null
            }
        NavHost(
            nav,
            startDestination = if (hasServer) Tab.HEUTE.route else SETUP_ROUTE,
            modifier = Modifier.padding(padding),
        ) {
            composable(SETUP_ROUTE) {
                val vm: SetupViewModel = viewModel(factory = requireNotNull(setupViewModelFactory))
                LaunchedEffect(pendingSetup) {
                    pendingSetup?.let { vm.prefill(it); onPendingSetupConsumed() }
                }
                SetupScreen(vm, onSaved = {
                    nav.navigate(Tab.HEUTE.route) { popUpTo(0) { inclusive = true } }
                })
            }
            if (tripViewModelFactory != null) {
                composable(Tab.HEUTE.route) {
                    val s = snapshot
                    val now = rememberMinuteNow(clock)
                    SyncedScreen(syncStatus, onRefresh, scrollable = s?.isEmpty == true) {
                        if (s == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            TodayScreen(
                                s, now,
                                onOpenDay = { nav.navigate(dayRoute(it)) },
                                onOpenTrip = { nav.navigateToTab(Tab.REISE) },
                                onSyncNow = onRefresh,
                                onOpenAttachment = { a -> attachmentActions?.onOpen?.invoke(a) },
                                onOpenLink = onOpenLink,
                                available = attachmentActions?.available ?: { true },
                            )
                        }
                    }
                }
                composable(Tab.REISE.route) {
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    TripErrors(vm, snackbar)
                    SyncedScreen(syncStatus, onRefresh, scrollable = snap?.isEmpty == true) {
                        val s = snap
                        when {
                            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            s.isEmpty -> EmptyState(onSyncNow = onRefresh)
                            else -> TripScreen(s, onOpenDay = { nav.navigate(dayRoute(it)) }, today = rememberMinuteNow(clock).toLocalDate().toString())
                        }
                    }
                }
                composable(DAY_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    TripErrors(vm, snackbar)
                    val dayFailures by vm.failures.collectAsState()
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
                                attachments = attachmentActions,
                                onSetTitle = vm::setDayTitle,
                                onSetNote = vm::setDayNote,
                                onSetRegion = vm::setRegionOverride,
                                onAddNote = vm::addNote,
                                failureCount = dayFailures,
                                onShowLegs = vm::setShowLegs,
                                onLegMode = vm::setLegMode,
                                onOpenLink = onOpenLink,
                                onAddSuggestionAt = vm::addSuggestionAt,
                                onAddNoteAt = vm::addNoteAt,
                                onSearchPlace = geoSearch?.let { { d, pos -> nav.navigate(daySearchRoute(d, pos.encode())) } },
                            )
                        }
                    }
                }
                composable(DAY_SEARCH_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val date = entry.arguments?.getString("date").orEmpty()
                    val position = ItemPosition.decode(entry.arguments?.getString("position"))
                    val geo = geoSearch
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        val s = snap
                        if (s != null && geo != null) {
                            val start = remember(date, position) { searchStart(s, date, (position as? ItemPosition.After)?.itemId) }
                            val model = rememberPlaceSearchModel(
                                search = { q, near, bbox -> geo.search(q, near, limit = 15, bbox = bbox) },
                                lookup = { lat, lng -> geo.reverse(lat, lng) },
                                home = start,
                            )
                            PlaceSearchScreen(
                                model = model,
                                start = start,
                                onAdd = { place -> vm.addSearchedPlace(date, position, place); nav.popBackStack() },
                                onBack = { nav.popBackStack() },
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
                    SyncedScreen(syncStatus, onRefresh, scrollable = snap?.isEmpty == true) {
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
                    val failures by vm.failures.collectAsState()
                    val id = entry.arguments?.getString("savedPlaceId").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let {
                            PlaceDetailScreen(it, id, onBack = { nav.popBackStack() }, onSaveNote = vm::setNote, onSetStatus = vm::setStatus, failureCount = failures, attachments = attachmentActions, onOpenLink = onOpenLink, onSetTags = vm::setTags, onSetRegion = vm::setRegion, onSetCategory = vm::setCategory, today = rememberMinuteNow(clock).toLocalDate().toString())
                        }
                    }
                }
            }
            if (moreViewModelFactory != null) {
                composable(Tab.LOGISTIK.route) {
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    SyncedScreen(syncStatus, onRefresh, scrollable = snap?.isEmpty == true) {
                        val s = snap
                        when {
                            s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            s.isEmpty -> EmptyState(onSyncNow = onRefresh)
                            else -> LogisticsScreen(
                                s,
                                onOpen = { e -> nav.navigate(logisticsDetailRoute(e.type, e.id)) },
                                today = rememberMinuteNow(clock).toLocalDate().toString(),
                            )
                        }
                    }
                }
                composable(LOGISTICS_DETAIL_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.LOGISTIK.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val type = logisticsTypeOf(entry.arguments?.getString("type"))
                    val id = entry.arguments?.getString("id").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s ->
                            // An unknown type segment gets the same "nicht gefunden" page (with back) as a missing id.
                            LogisticsDetailScreen(
                                s, type, id,
                                onBack = { nav.popBackStack() },
                                attachments = attachmentActions,
                                onOpenLink = onOpenLink,
                                today = rememberMinuteNow(clock).toLocalDate().toString(),
                                onEdit = { t, i -> nav.navigate(logisticsEditRoute(t, i)) },
                                onMerge = vm::mergeMoves,
                                onDelete = { t, i -> vm.deleteMove(t, i); nav.popBackStack() },
                            )
                        }
                    }
                }
                composable(LOGISTICS_EDIT_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.LOGISTIK.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val type = logisticsTypeOf(entry.arguments?.getString("type"))
                    val id = entry.arguments?.getString("id").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s ->
                            LogisticsEditScreen(s, type, id, onBack = { nav.popBackStack() }, onSave = vm::saveFields, search = placeSearch)
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
                    // SettingsScreen scrolls itself; a scrollable wrapper would nest two vertical scrolls (crash).
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        SettingsScreen(
                            info = settingsInfo,
                            status = syncStatus,
                            onChangeServer = { nav.navigate(SETUP_ROUTE) },
                            onSyncNow = onRefresh,
                            onResetSync = onResetSync,
                            tripZone = tripZone,
                            onTripZone = onTripZone,
                            remindersEnabled = remindersEnabled,
                            onRemindersEnabled = onRemindersEnabled,
                            onReloadMaps = {
                                uiScope.launch {
                                    onReloadMapImages()
                                    snackbar.showSnackbar(MSG_RELOAD_MAPS)
                                }
                            },
                        )
                    }
                }
            }
            if (attachmentFiles != null) {
                composable(ATTACHMENT_ROUTE) { entry ->
                    val id = entry.arguments?.getString("attachmentId").orEmpty()
                    AttachmentViewerScreen(snapshot?.attachments?.firstOrNull { it.id == id }, attachmentFiles, onBack = { nav.popBackStack() })
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
