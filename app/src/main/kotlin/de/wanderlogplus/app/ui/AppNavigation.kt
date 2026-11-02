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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.TwoPane
import de.wanderlogplus.app.ui.design.isWideLayout
import de.wanderlogplus.app.ui.trip.defaultDayFor
import de.wanderlogplus.shared.sync.EntityType
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
import de.wanderlogplus.app.ui.places.PLACES_SEARCH_ROUTE
import de.wanderlogplus.app.ui.places.ALL_REGIONS
import de.wanderlogplus.app.ui.places.NO_REGION
import de.wanderlogplus.app.ui.places.nearestRegion
import de.wanderlogplus.app.ui.places.placesSearchRoute
import de.wanderlogplus.app.ui.places.regionCentres
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
import de.wanderlogplus.app.ui.reels.REELS_ROUTE
import de.wanderlogplus.app.ui.reels.REEL_ROUTE
import de.wanderlogplus.app.ui.reels.ReelJobScreen
import de.wanderlogplus.app.ui.reels.ReelsScreen
import de.wanderlogplus.app.ui.reels.ReelsViewModel
import de.wanderlogplus.app.ui.reels.reelRoute
import de.wanderlogplus.app.ui.more.SettingsInfo
import de.wanderlogplus.app.data.TripZone
import de.wanderlogplus.app.ui.more.SettingsScreen
import de.wanderlogplus.app.ui.more.noteRoute
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.app.ui.today.rememberMinuteNow
import de.wanderlogplus.app.ui.trip.DAY_ROUTE
import de.wanderlogplus.app.ui.trip.DAY_SEARCH_ROUTE
import de.wanderlogplus.app.ui.trip.DAY_MAP_ROUTE
import de.wanderlogplus.app.ui.trip.DayMapScreen
import de.wanderlogplus.app.ui.trip.dayMapRoute
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
            REISE -> setOf(DAY_ROUTE, DAY_SEARCH_ROUTE, DAY_MAP_ROUTE)
            ORTE -> setOf(REGION_ROUTE, PLACE_ROUTE, PLACES_SEARCH_ROUTE, de.wanderlogplus.app.ui.places.PLACE_MAP_ROUTE)
            LOGISTIK -> setOf(LOGISTICS_DETAIL_ROUTE, LOGISTICS_EDIT_ROUTE)
            MEHR -> setOf(NOTES_ROUTE, NOTE_ROUTE, IMPORT_ROUTE, CONFLICTS_ROUTE, SETTINGS_ROUTE, REELS_ROUTE, REEL_ROUTE, de.wanderlogplus.app.ui.reels.REEL_POSITION_ROUTE, de.wanderlogplus.app.ui.more.AI_SETTINGS_ROUTE, de.wanderlogplus.app.ui.reels.JOB_ROUTE, de.wanderlogplus.app.ui.reels.JOBS_ROUTE)
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
    /** Criteria and extra prompt for the assistant (Mehr → KI-Kriterien). */
    assessConfig: de.wanderlogplus.shared.sync.AssessConfig = de.wanderlogplus.shared.sync.AssessConfig(),
    onAssessConfig: (de.wanderlogplus.shared.sync.AssessConfig) -> Unit = {},
    /** Trip-zone "now" for the Heute tab; injectable for tests. */
    clock: () -> LocalDateTime = { LocalDateTime.now() },
    /** Attachments (add, open, delete, viewer); without both, no attachment UI is shown. */
    attachmentsViewModelFactory: ViewModelProvider.Factory? = null,
    attachmentFiles: AttachmentFiles? = null,
    /** Server settings from a setup deep link; shown on the setup screen (prefill only), then [onPendingSetupConsumed]. */
    pendingSetup: ServerSettings? = null,
    onPendingSetupConsumed: () -> Unit = {},
    /** Reel analyses (Mehr → Reels); null hides the screens. */
    reelsViewModelFactory: ViewModelProvider.Factory? = null,
    /** A reel job started from the share sheet: shown once, then [onPendingReelConsumed]. */
    pendingReelJob: String? = null,
    onPendingReelConsumed: () -> Unit = {},
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
    LaunchedEffect(pendingReelJob, graphReady) {
        if (!graphReady || pendingReelJob == null || reelsViewModelFactory == null) return@LaunchedEffect
        nav.navigate(reelRoute(pendingReelJob))
        onPendingReelConsumed()
    }

    val snackbar = remember { SnackbarHostState() }
    // Unfolded (or tablet/landscape): navigation rail on the left and two panes where a screen has list and detail.
    val wide = isWideLayout()
    val onTab: (Tab) -> Unit = { tab ->
        if (!(current in tab.nestedRoutes && nav.popBackStack(tab.route, inclusive = false))) nav.navigateToTab(tab)
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (current != SETUP_ROUTE && !wide) NavigationBar {
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
        @Composable
        fun DayDetailFor(vm: TripViewModel, snap: TripSnapshot, date: String, onBack: (() -> Unit)?) {
            val dayFailures by vm.failures.collectAsState()
            DayDetailScreen(
                snapshot = snap,
                date = date,
                onBack = onBack,
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
                onOpenMap = { d -> nav.navigate(dayMapRoute(d)) },
                onAddStayAt = vm::addStayAt,
            )
        }

        @Composable
        fun PlaceDetailFor(vm: PlacesViewModel, snap: TripSnapshot, id: String, onBack: (() -> Unit)?) {
            val failures by vm.failures.collectAsState()
            val researching by vm.researching.collectAsState()
            val researchJob by vm.researchJob.collectAsState()
            ResearchSnack(vm, snackbar)
            val showProgress: (() -> Unit)? = researchJob?.takeIf { reelsViewModelFactory != null }?.let { j -> { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(j)) } }
            PlaceDetailScreen(
                snap, id, onBack = onBack, onSaveNote = vm::setNote, onSetStatus = vm::setStatus, failureCount = failures, attachments = attachmentActions,
                onOpenLink = onOpenLink, onSetTags = vm::setTags, onSetRegion = vm::setRegion, onSetCategory = vm::setCategory,
                today = rememberMinuteNow(clock).toLocalDate().toString(),
                research = if (vm.canAssess) de.wanderlogplus.app.ui.places.ResearchAction(id in researching, { vm.research(listOf(id)) }, showProgress) else null,
                onOpenMap = { nav.navigate(de.wanderlogplus.app.ui.places.placeMapRoute(id)) },
            )
        }

        @Composable
        fun RegionFor(vm: PlacesViewModel, snap: TripSnapshot, key: String, onOpenPlace: (String) -> Unit) {
            val researching by vm.researching.collectAsState()
            val status by vm.researchStatus.collectAsState()
            val researchJob by vm.researchJob.collectAsState()
            ResearchSnack(vm, snackbar)
            RegionPlacesScreen(
                snap, key, onBack = { nav.popBackStack() }, onOpenPlace = onOpenPlace,
                onSearch = geoSearch?.let { { nav.navigate(placesSearchRoute(key)) } },
                onResearch = if (vm.canAssess) vm::research else null,
                researchStatus = status.takeIf { researching.isNotEmpty() },
                onShowProgress = researchJob?.takeIf { reelsViewModelFactory != null }?.let { j -> { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(j)) } },
            )
        }

        @Composable
        fun LogisticsDetailFor(vm: MoreViewModel, snap: TripSnapshot, type: EntityType?, id: String, onBack: (() -> Unit)?, afterDelete: () -> Unit) {
            LogisticsDetailScreen(
                snap, type, id,
                onBack = onBack,
                attachments = attachmentActions,
                onOpenLink = onOpenLink,
                today = rememberMinuteNow(clock).toLocalDate().toString(),
                onEdit = { t, i -> nav.navigate(logisticsEditRoute(t, i)) },
                onMerge = vm::mergeMoves,
                onDelete = { t, i -> vm.deleteMove(t, i); afterDelete() },
            )
        }

        Row(Modifier.padding(padding).fillMaxSize()) {
        if (wide && current != SETUP_ROUTE) {
            NavigationRail(modifier = Modifier.testTag("nav-rail")) {
                Tab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = current == tab.route || current in tab.nestedRoutes,
                        onClick = { onTab(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag("rail-${tab.route}"),
                    )
                }
            }
        }
        NavHost(
            nav,
            startDestination = if (hasServer) Tab.HEUTE.route else SETUP_ROUTE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
                            wide -> {
                                val today = rememberMinuteNow(clock).toLocalDate().toString()
                                var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
                                val date = selectedDay ?: defaultDayFor(s, today)
                                TwoPane(
                                    list = { TripScreen(s, onOpenDay = { selectedDay = it }, today = today, selected = date) },
                                    detail = { if (date != null) DayDetailFor(vm, s, date, onBack = null) },
                                )
                            }
                            else -> TripScreen(s, onOpenDay = { nav.navigate(dayRoute(it)) }, today = rememberMinuteNow(clock).toLocalDate().toString())
                        }
                    }
                }
                composable(DAY_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    TripErrors(vm, snackbar)
                    val date = entry.arguments?.getString("date").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { DayDetailFor(vm, it, date, onBack = { nav.popBackStack() }) }
                    }
                }
                composable(DAY_MAP_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val date = entry.arguments?.getString("date").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s -> DayMapScreen(s, date, onBack = { nav.popBackStack() }) }
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
                            else -> {
                                val assessing by vm.assessing.collectAsState()
                                PlacesScreen(
                                    s,
                                    onOpenRegion = { nav.navigate(regionRoute(it)) },
                                    onSearch = geoSearch?.let { { nav.navigate(placesSearchRoute(ALL_REGIONS)) } },
                                    onAssess = if (vm.canAssess) vm::assessAll else null,
                                    assessing = assessing,
                                    onReels = reelsViewModelFactory?.let { { nav.navigate(REELS_ROUTE) } },
                                )
                            }
                        }
                    }
                }
                composable(PLACES_SEARCH_ROUTE) { entry ->
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.ORTE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val key = entry.arguments?.getString("regionKey").orEmpty()
                    val geo = geoSearch
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        val s = snap
                        if (s != null && geo != null) {
                            val start = remember(key) {
                                val centres = regionCentres(s)
                                centres[key] ?: centres.values.takeIf { it.isNotEmpty() }?.let { c ->
                                    de.wanderlogplus.app.maps.LatLngD(c.map { it.lat }.average(), c.map { it.lng }.average())
                                }
                            }
                            val model = rememberPlaceSearchModel(
                                search = { q, near, bbox -> geo.search(q, near, limit = 15, bbox = bbox) },
                                lookup = { lat, lng -> geo.reverse(lat, lng) },
                                home = start,
                            )
                            PlaceSearchScreen(
                                model = model,
                                start = start,
                                addLabel = "Zu den Orten hinzufügen",
                                onAdd = { place ->
                                    val region = when (key) {
                                        ALL_REGIONS, NO_REGION -> nearestRegion(s, place.lat, place.lng)
                                        else -> key
                                    }
                                    vm.addFoundPlace(place, region)
                                    nav.popBackStack()
                                },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
                composable(REGION_ROUTE) { entry ->
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.ORTE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val key = entry.arguments?.getString("regionKey").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let {
                            if (wide) {
                                var selectedPlace by rememberSaveable(key) { mutableStateOf<String?>(null) }
                                TwoPane(
                                    list = { RegionFor(vm, it, key, onOpenPlace = { id -> selectedPlace = id }) },
                                    detail = {
                                        val id = selectedPlace
                                        if (id == null) EmptyHint("Ort in der Liste antippen", Modifier.padding(24.dp))
                                        else PlaceDetailFor(vm, it, id, onBack = null)
                                    },
                                )
                            } else {
                                RegionFor(vm, it, key, onOpenPlace = { id -> nav.navigate(placeRoute(id)) })
                            }
                        }
                    }
                }
                composable(de.wanderlogplus.app.ui.places.PLACE_MAP_ROUTE) { entry ->
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.ORTE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val id = entry.arguments?.getString("savedPlaceId").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { de.wanderlogplus.app.ui.places.PlaceMapScreen(it, id, onBack = { nav.popBackStack() }) }
                    }
                }
                composable(PLACE_ROUTE) { entry ->
                    val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.ORTE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    ErrorSnack(vm.error, vm::consumeError, snackbar)
                    val id = entry.arguments?.getString("savedPlaceId").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { PlaceDetailFor(vm, it, id, onBack = { nav.popBackStack() }) }
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
                            wide -> {
                                var selected by rememberSaveable { mutableStateOf<String?>(null) } // "TYPE/id"
                                val sel = selected?.split('/', limit = 2)?.takeIf { it.size == 2 }
                                TwoPane(
                                    list = {
                                        LogisticsScreen(
                                            s,
                                            onOpen = { e -> selected = "${e.type.name}/${e.id}" },
                                            today = rememberMinuteNow(clock).toLocalDate().toString(),
                                        )
                                    },
                                    detail = {
                                        if (sel == null) EmptyHint("Fahrt oder Unterkunft in der Liste antippen", Modifier.padding(24.dp))
                                        else LogisticsDetailFor(vm, s, logisticsTypeOf(sel[0]), sel[1], onBack = null, afterDelete = { selected = null })
                                    },
                                )
                            }
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
                            LogisticsDetailFor(vm, s, type, id, onBack = { nav.popBackStack() }, afterDelete = { nav.popBackStack() })
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
                if (reelsViewModelFactory != null) {
                    composable(REELS_ROUTE) {
                        val vm: ReelsViewModel = viewModel(factory = reelsViewModelFactory)
                        val jobs by vm.jobs.collectAsState()
                        val msg by vm.message.collectAsState()
                        LaunchedEffect(Unit) { vm.refreshList() }
                        LaunchedEffect(msg) { msg?.let { vm.consumeMessage(); snackbar.showSnackbar(it) } }
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            ReelsScreen(
                                jobs = jobs,
                                isImported = vm::isImported,
                                onStart = { url -> vm.start(url) { id -> nav.navigate(reelRoute(id)) } },
                                onOpen = { id -> nav.navigate(reelRoute(id)) },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                    composable(REEL_ROUTE) { entry ->
                        val vm: ReelsViewModel = viewModel(factory = reelsViewModelFactory)
                        val id = entry.arguments?.getString("jobId").orEmpty()
                        val job by vm.job.collectAsState()
                        val snap by vm.snapshot.collectAsState()
                        val imported by vm.importedNow.collectAsState()
                        val positions by vm.positions.collectAsState()
                        val msg by vm.message.collectAsState()
                        LaunchedEffect(id) { vm.watch(id) }
                        LaunchedEffect(msg) { msg?.let { vm.consumeMessage(); snackbar.showSnackbar(it) } }
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            ReelJobScreen(
                                job = job?.takeIf { it.id == id }?.let { j ->
                                    j.copy(candidates = j.candidates.mapIndexed { i, c -> positions[i]?.let { p -> de.wanderlogplus.app.ui.reels.withPosition(c, p) } ?: c })
                                },
                                snapshot = snap,
                                imported = imported,
                                onImport = { picks -> vm.import(id, picks, job?.source) },
                                onOpenPlaces = { nav.navigateToTab(Tab.ORTE) },
                                onBack = { nav.popBackStack() },
                                onShowProgress = { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(id)) },
                                onFindPosition = geoSearch?.let { { i -> nav.navigate(de.wanderlogplus.app.ui.reels.reelPositionRoute(id, i)) } },
                            )
                        }
                    }
                    composable(de.wanderlogplus.app.ui.reels.REEL_POSITION_ROUTE) { entry ->
                        // The reel screen below owns the view model that keeps the picked positions.
                        val owner = nav.previousBackStackEntry ?: entry
                        val vm: ReelsViewModel = viewModel(factory = reelsViewModelFactory, viewModelStoreOwner = owner)
                        val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                        val job by vm.job.collectAsState()
                        val snap by vm.snapshot.collectAsState()
                        val c = job?.candidates?.getOrNull(index)
                        val geo = geoSearch
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            if (c != null && geo != null) {
                                val start = remember(snap) {
                                    snap?.let { regionCentres(it) }?.values?.takeIf { it.isNotEmpty() }?.let { cs ->
                                        de.wanderlogplus.app.maps.LatLngD(cs.map { it.lat }.average(), cs.map { it.lng }.average())
                                    } ?: de.wanderlogplus.app.maps.LatLngD(36.2, 138.25) // never a world map: its area is no search filter
                                }
                                val model = rememberPlaceSearchModel(
                                    search = { q, near, bbox -> geo.search(q, near, limit = 15, bbox = bbox) },
                                    lookup = { lat, lng -> geo.reverse(lat, lng) },
                                    home = start,
                                )
                                LaunchedEffect(index) {
                                    if (model.query.isBlank()) {
                                        model.query = listOfNotNull(c.name, c.city).joinToString(" ")
                                        model.submit()
                                    }
                                }
                                PlaceSearchScreen(
                                    model = model,
                                    start = start,
                                    addLabel = "Als Position übernehmen",
                                    onAdd = { place -> vm.setPosition(index, place); nav.popBackStack() },
                                    onBack = { nav.popBackStack() },
                                )
                            }
                        }
                    }
                    composable(de.wanderlogplus.app.ui.reels.JOB_ROUTE) { entry ->
                        // Own view model per screen: watching this job must not switch another screen's job.
                        val vm: ReelsViewModel = viewModel(factory = reelsViewModelFactory)
                        val id = entry.arguments?.getString("jobId").orEmpty()
                        val job by vm.job.collectAsState()
                        val msg by vm.message.collectAsState()
                        LaunchedEffect(id) { vm.watch(id) }
                        LaunchedEffect(msg) { msg?.let { vm.consumeMessage(); snackbar.showSnackbar(it) } }
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            val j = job?.takeIf { it.id == id }
                            de.wanderlogplus.app.ui.reels.JobProgressScreen(
                                job = j,
                                onBack = { nav.popBackStack() },
                                onOpenResult = if (j?.kind == "reel") { { nav.navigate(reelRoute(id)) } } else null,
                            )
                        }
                    }
                    composable(de.wanderlogplus.app.ui.reels.JOBS_ROUTE) {
                        val vm: ReelsViewModel = viewModel(factory = reelsViewModelFactory)
                        val jobs by vm.jobs.collectAsState()
                        LaunchedEffect(Unit) {
                            while (true) {
                                vm.refreshList(kind = null)
                                kotlinx.coroutines.delay(5_000)
                            }
                        }
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            de.wanderlogplus.app.ui.reels.AiJobsScreen(jobs, onOpen = { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(it)) }, onBack = { nav.popBackStack() })
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
                composable(de.wanderlogplus.app.ui.more.AI_SETTINGS_ROUTE) {
                    SyncedScreen(syncStatus, onRefresh) {
                        de.wanderlogplus.app.ui.more.AiSettingsScreen(assessConfig, onAssessConfig)
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
                            onOpenAiSettings = { nav.navigate(de.wanderlogplus.app.ui.more.AI_SETTINGS_ROUTE) },
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
}

/** Shows the outcome of a finished place research once ("3 Orte recherchiert"). */
@Composable
private fun ResearchSnack(vm: PlacesViewModel, snackbar: SnackbarHostState) {
    val status by vm.researchStatus.collectAsState()
    val running by vm.researching.collectAsState()
    LaunchedEffect(status, running.isEmpty()) {
        val s = status
        if (s != null && running.isEmpty()) {
            vm.consumeResearchStatus()
            snackbar.showSnackbar(s)
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
