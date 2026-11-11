package de.wanderlogplus.app.ui

import androidx.compose.foundation.layout.consumeWindowInsets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import de.wanderlogplus.app.ui.chat.placeCategory
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
    BUDGET("budget", "Budget", Icons.Filled.AccountBalanceWallet),
    MEHR("mehr", "Mehr", Icons.Filled.MoreHoriz),
    ;

    /** Routes nested below this tab's root (the tab stays highlighted there). */
    val nestedRoutes: Set<String>
        get() = when (this) {
            REISE -> setOf(DAY_ROUTE, DAY_SEARCH_ROUTE, DAY_MAP_ROUTE, de.wanderlogplus.app.ui.transit.TRANSIT_ROUTE, TRIP_PLACE_ROUTE)
            ORTE -> setOf(REGION_ROUTE, PLACE_ROUTE, PLACES_SEARCH_ROUTE, de.wanderlogplus.app.ui.places.PLACE_MAP_ROUTE)
            LOGISTIK -> setOf(LOGISTICS_DETAIL_ROUTE, LOGISTICS_EDIT_ROUTE, de.wanderlogplus.app.ui.transit.TRANSIT_REPLACE_ROUTE, de.wanderlogplus.app.ui.transit.JOURNEY_CHANGE_ROUTE)
            MEHR -> setOf(de.wanderlogplus.app.ui.guides.GUIDES_ROUTE, de.wanderlogplus.app.ui.guides.GUIDE_ROUTE, NOTES_ROUTE, NOTE_ROUTE, IMPORT_ROUTE, CONFLICTS_ROUTE, SETTINGS_ROUTE, REELS_ROUTE, REEL_ROUTE, de.wanderlogplus.app.ui.more.ACCESS_ROUTE, de.wanderlogplus.app.ui.reels.REEL_POSITION_ROUTE, de.wanderlogplus.app.ui.more.AI_SETTINGS_ROUTE, de.wanderlogplus.app.ui.reels.JOB_ROUTE, de.wanderlogplus.app.ui.reels.JOBS_ROUTE, de.wanderlogplus.app.ui.vault.VAULT_ROUTE)
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
    /** Google first (via the server), OpenStreetMap as fallback; null: Photon only. */
    placeFinder: de.wanderlogplus.client.PlaceFinder? = null,
    /** Google search via the server for linking places; null (no server) hides „Suchen“. */
    googleSearch: de.wanderlogplus.app.ui.places.GoogleSearch? = null,
    /** Online photo search via the server; null (no server) offers only the gallery. */
    webPhotoSearch: de.wanderlogplus.app.ui.attachments.WebPhotoSearch? = null,
    webPhotoFetch: de.wanderlogplus.app.ui.attachments.WebPhotoFetch? = null,
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
    /** Media upload (Mehr → Bilder-Upload); null hides the row. */
    vaultScanViewModelFactory: ViewModelProvider.Factory? = null,
    /** An admin token is stored; together with [vaultScanViewModelFactory] it shows the Bilder-Upload row. */
    hasAdminToken: Boolean = false,
    /** A reel job started from the share sheet: shown once, then [onPendingReelConsumed]. */
    pendingReelJob: String? = null,
    onPendingReelConsumed: () -> Unit = {},
    /** Deletes the cached route map pictures ("Kartenbilder neu laden" in Einstellungen). */
    onReloadMapImages: suspend () -> Unit = {},
    /** Server API for "Verbindung abfragen" (public transport in Japan); null hides those buttons. */
    transitApi: (suspend () -> de.wanderlogplus.client.SyncApi?)? = null,
    /** Debug mode: the pretended "now" of the Heute screen (null = real time). */
    simulatedTime: de.wanderlogplus.app.data.SimulatedTime? = null,
    /** Sets or clears it; null outside debug mode (hides the controls). */
    onSimulatedTime: ((de.wanderlogplus.app.data.SimulatedTime?) -> Unit)? = null,
    /** "Zugänge verwalten" (admin token); null hides it. */
    accessViewModelFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    /** The connection in use, for "QR-Code anzeigen" in Einstellungen. */
    currentServer: ServerSettings? = null,
    /** The app runs with the separate debug profile (own server and data). */
    debugMode: Boolean = false,
    /** Switches the profile (restarts the app); null hides the switch. */
    onDebugMode: ((Boolean) -> Unit)? = null,
    /** Installed app version, shown in Einstellungen. */
    appVersion: String = "",
    /** "Nach Updates suchen"; null hides it. */
    onCheckUpdates: (() -> Unit)? = null,
    /** The trip chat (assistant); null hides the chat buttons. */
    chatViewModelFactory: ViewModelProvider.Factory? = null,
    /** The budget (costs, splits); null hides the Kosten sections and leaves the Budget tab read-only. */
    budgetViewModelFactory: ViewModelProvider.Factory? = null,
    /** Travel guides (suggestions, jobs, pins); null: guides are read-only and cannot be created. */
    guidesViewModelFactory: ViewModelProvider.Factory? = null,
    /** Research of a stay's room and amenities; null hides "Recherchieren" on stays. */
    stayInfoViewModelFactory: ViewModelProvider.Factory? = null,
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
    val chatVm: de.wanderlogplus.app.ui.chat.ChatViewModel? = chatViewModelFactory?.let { viewModel(factory = it) }
    val budgetVm: de.wanderlogplus.app.ui.budget.BudgetViewModel? = budgetViewModelFactory?.let { viewModel(factory = it) }
    val guidesVm: de.wanderlogplus.app.ui.guides.GuidesViewModel? = guidesViewModelFactory?.let { viewModel(factory = it) }
    val stayInfoVm: de.wanderlogplus.app.ui.logistics.StayInfoViewModel? = stayInfoViewModelFactory?.let { viewModel(factory = it) }
    val stayInfoMessage = stayInfoVm?.message?.collectAsState()?.value
    val stayResearchJob = stayInfoVm?.running?.collectAsState()?.value
    LaunchedEffect(stayInfoMessage) {
        if (stayInfoMessage != null) {
            snackbar.showSnackbar(stayInfoMessage)
            stayInfoVm.messageShown()
        }
    }
    val guidesMessage = guidesVm?.message?.collectAsState()?.value
    val guideJobs = guidesVm?.jobs?.collectAsState()?.value.orEmpty()
    LaunchedEffect(guidesMessage) {
        if (guidesMessage != null) {
            snackbar.showSnackbar(guidesMessage)
            guidesVm.messageShown()
        }
    }
    val budgetMessage = budgetVm?.message?.collectAsState()?.value
    LaunchedEffect(budgetMessage) {
        if (budgetMessage != null) {
            snackbar.showSnackbar(budgetMessage)
            budgetVm.messageShown()
        }
    }
    val chatToday = rememberMinuteNow(clock).toLocalDate().toString()
    val chatPane = remember { de.wanderlogplus.app.ui.chat.ChatPaneHolder() }
    val openChat: (() -> Unit)? = chatVm?.takeIf { current != SETUP_ROUTE && current != de.wanderlogplus.app.ui.chat.CHAT_ROUTE }?.let { vm ->
        {
            // A two-pane screen: the detail beside the list is what the user looks at.
            val pane = chatPane.paneFor(current)
            val args = backStack?.arguments
            val map = pane?.args ?: listOf("date", "savedPlaceId", "regionKey").associateWith { args?.getString(it) }
            vm.openFrom(de.wanderlogplus.app.ui.chat.chatContextFor(pane?.route ?: current, map, snapshot, chatToday))
            nav.navigate(de.wanderlogplus.app.ui.chat.CHAT_ROUTE) { launchSingleTop = true }
        }
    }
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
                rememberAttachmentActions(
                    avm, attachmentFiles, snackbar, webSearch = webPhotoSearch, webFetch = webPhotoFetch,
                    queryFor = { t -> snapshot?.let { de.wanderlogplus.app.ui.attachments.photoQuery(it, t) } ?: de.wanderlogplus.app.ui.attachments.PhotoQuery("", null) },
                ) { a -> nav.navigate(attachmentRoute(a.id)) }
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
                onLegMode = { key, mode -> vm.setLegMode(date, key, mode) },
                onOpenLink = onOpenLink,
                onAddSuggestionAt = vm::addSuggestionAt,
                onAddNoteAt = vm::addNoteAt,
                onSearchPlace = geoSearch?.let { { d, pos -> nav.navigate(daySearchRoute(d, pos.encode())) } },
                onOpenMap = { d -> nav.navigate(dayMapRoute(d)) },
                onTransitDetails = transitApi?.let { { d, id -> nav.navigate(de.wanderlogplus.app.ui.transit.transitRoute(d, id)) } },
                onOpenPhoto = attachmentFiles?.let { { t: EntityType, id: String, i: Int -> nav.navigate(de.wanderlogplus.app.ui.attachments.photosRoute(t, id, i)) } },
                onOpenPlace = placesViewModelFactory?.let {
                    { placeId ->
                        uiScope.launch { vm.savedPlaceFor(placeId)?.let { id -> nav.navigate(tripPlaceRoute(id)) } }
                        Unit
                    }
                },
                onAddStayAt = vm::addStayAt,
                onRestoreVersion = { d, at -> vm.restoreVersion(d, at) },
                onSetLinks = vm::setDayLinks,
            )
        }

        @Composable
        fun PlaceDetailFor(vm: PlacesViewModel, snap: TripSnapshot, id: String, onBack: (() -> Unit)?, onOpenSibling: ((String) -> Unit)? = null) {
            val failures by vm.failures.collectAsState()
            val researching by vm.researching.collectAsState()
            val researchJob by vm.researchJob.collectAsState()
            ResearchSnack(vm, snackbar)
            val showProgress: (() -> Unit)? = researchJob?.takeIf { reelsViewModelFactory != null }?.let { j -> { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(j)) } }
            PlaceDetailScreen(
                snap, id, onBack = onBack, onSaveNote = vm::setNote, onSetStatus = vm::setStatus, failureCount = failures, attachments = attachmentActions,
                onOpenLink = onOpenLink, onSetTags = vm::setTags, onSetRegion = vm::setRegion, onSetCategory = vm::setCategory,
                onSetHeaderImage = vm::setHeaderImage, onSetLinks = vm::setLinks,
                onSetReserved = vm::setReserved,
                onSetReservationWindow = vm::setReservationWindow,
                me = vm.me.collectAsState().value,
                onVote = vm::vote,
                onDelete = { sid -> vm.delete(sid); onBack?.invoke() },
                today = rememberMinuteNow(clock).toLocalDate().toString(),
                research = if (vm.canAssess) de.wanderlogplus.app.ui.places.ResearchAction(id in researching, { vm.research(listOf(id)) }, showProgress) else null,
                onOpenMap = { nav.navigate(de.wanderlogplus.app.ui.places.placeMapRoute(id)) },
                onOpenPhoto = attachmentFiles?.let { { t: EntityType, tid: String, i: Int -> nav.navigate(de.wanderlogplus.app.ui.attachments.photosRoute(t, tid, i)) } },
                onSetHeaderFromAttachment = vm::setHeaderFromAttachment,
                googleSearch = googleSearch,
                onLinkGoogle = vm::linkGoogle,
                onOpenSibling = onOpenSibling,
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
                onResearch = if (vm.canAssess) { ids: List<String> -> vm.research(ids, snap.regions.firstOrNull { it.id == key }?.name ?: "Ohne Region") } else null,
                researchStatus = status.takeIf { researching.isNotEmpty() },
                onShowProgress = researchJob?.takeIf { reelsViewModelFactory != null }?.let { j -> { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(j)) } },
            )
        }

        @Composable
        fun LogisticsDetailFor(vm: MoreViewModel, snap: TripSnapshot, type: EntityType?, id: String, onBack: (() -> Unit)?, afterDelete: () -> Unit) {
            LogisticsDetailScreen(
                snap, type, id,
                onBack = onBack,
                onReplaceRide = transitApi?.let { { tid: String -> nav.navigate(de.wanderlogplus.app.ui.transit.transitReplaceRoute(tid)) } },
                onSeatsConfirmed = vm::confirmSeats,
                attachments = attachmentActions,
                onOpenLink = onOpenLink,
                today = rememberMinuteNow(clock).toLocalDate().toString(),
                onEdit = { t, i -> nav.navigate(logisticsEditRoute(t, i)) },
                onMerge = vm::mergeMoves,
                onDelete = { t, i -> vm.deleteMove(t, i); afterDelete() },
                onSetHeaderImage = vm::setHeaderImage,
                onSetLinks = vm::setLinks,
                onOpenJourney = { j -> nav.navigate(de.wanderlogplus.app.ui.logistics.journeyRoute(j)) },
                onJoin = vm::joinJourney,
                onSplit = vm::splitLegacy,
                onStayResearch = stayInfoVm?.let { s -> s::research },
                onSetPhotoLabel = vm::setPhotoLabel,
                onOpenPhoto = if (type == EntityType.STAY) ({ i: Int -> nav.navigate(de.wanderlogplus.app.ui.attachments.photosRoute(EntityType.STAY, id, i)) }) else null,
                stayResearchJob = stayResearchJob,
                onSetHeaderFromAttachment = vm::setHeaderFromAttachment,
                googleSearch = googleSearch,
                onLinkGoogle = vm::linkGoogle,
            )
        }

        @Composable
        fun JourneyFor(vm: MoreViewModel, snap: TripSnapshot, journeyId: String, onBack: (() -> Unit)?) {
            de.wanderlogplus.app.ui.logistics.JourneyScreen(
                snap, journeyId, onBack = onBack,
                onOpenLeg = { t, i -> nav.navigate(logisticsDetailRoute(t, i)) },
                onAddLeg = { t, prev, next -> nav.navigate(logisticsEditRoute(t, de.wanderlogplus.app.ui.logistics.newLegId(journeyId, prev, next))) },
                onJoin = vm::joinJourney,
                onLeave = vm::leaveJourney,
                onDissolve = { j -> vm.dissolveJourney(j); onBack?.invoke() },
                onOpenAttachment = { a -> attachmentActions?.onOpen?.invoke(a) },
                onMoveLeg = { i, up -> vm.moveJourneyLeg(journeyId, i, up) },
                onReplaceRide = transitApi?.let { { tid: String -> nav.navigate(de.wanderlogplus.app.ui.transit.transitReplaceRoute(tid)) } },
                onChangeJourney = transitApi?.let { { nav.navigate(de.wanderlogplus.app.ui.transit.journeyChangeRoute(journeyId)) } },
            )
        }

        Row(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
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
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).fillMaxHeight()) {
        // Tab start screens have no photo header: a slim bar carries their chat button.
        if (openChat != null && current in CHAT_BAR_ROUTES) {
            de.wanderlogplus.app.ui.chat.ChatTopBar(Tab.entries.firstOrNull { it.route == current }?.label.orEmpty(), openChat)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            de.wanderlogplus.app.ui.design.LocalOpenChat provides openChat,
            de.wanderlogplus.app.ui.chat.LocalChatPane provides chatPane,
            de.wanderlogplus.app.ui.budget.LocalBudgetActions provides budgetVm,
            de.wanderlogplus.app.ui.guides.LocalGuideActions provides guidesVm,
            de.wanderlogplus.app.ui.guides.LocalGuideJobs provides guideJobs,
            de.wanderlogplus.app.ui.guides.LocalOpenGuide provides { id: String -> nav.navigate(de.wanderlogplus.app.ui.guides.guideRoute(id)) },
            de.wanderlogplus.app.ui.guides.LocalOpenGuides provides { nav.navigate(de.wanderlogplus.app.ui.guides.GUIDES_ROUTE) },
        ) {
        NavHost(
            nav,
            startDestination = if (hasServer) Tab.HEUTE.route else SETUP_ROUTE,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (chatVm != null) {
                composable(de.wanderlogplus.app.ui.chat.CHAT_ROUTE) {
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    val tripVm: TripViewModel? = tripViewModelFactory?.let { viewModel(factory = it) }
                    val live = tripVm?.snapshot?.collectAsState()?.value ?: snapshot
                    tripVm?.let { TripErrors(it, snackbar) }
                    val liveNow = androidx.compose.runtime.rememberUpdatedState(live)
                    val actions = tripVm?.let { vm -> remember(vm) { chatActions(vm, { liveNow.value }) { msg -> uiScope.launch { snackbar.showSnackbar(msg) } } } }
                    de.wanderlogplus.app.ui.chat.ChatRoute(
                        chatVm, live,
                        actions = actions,
                        onBack = { nav.popBackStack() },
                        onOpenPlace = { id -> nav.navigate(if (placesViewModelFactory != null) placeRoute(id) else tripPlaceRoute(id)) },
                        onOpenUrl = { url -> if (runCatching { uri.openUri(url) }.isFailure) uiScope.launch { snackbar.showSnackbar(MSG_NO_APP) } },
                        onOpenRef = { r ->
                            val R = de.wanderlogplus.shared.sync.ChatRef
                            when (r.type) {
                                R.PLACE -> nav.navigate(if (placesViewModelFactory != null) placeRoute(r.id) else tripPlaceRoute(r.id))
                                R.DAY -> nav.navigate(de.wanderlogplus.app.ui.trip.dayRoute(r.id))
                                R.STAY -> nav.navigate(de.wanderlogplus.app.ui.logistics.logisticsDetailRoute(de.wanderlogplus.shared.sync.EntityType.STAY, r.id))
                                R.TRAIN -> nav.navigate(de.wanderlogplus.app.ui.logistics.logisticsDetailRoute(de.wanderlogplus.shared.sync.EntityType.TRAIN, r.id))
                                R.FLIGHT -> nav.navigate(de.wanderlogplus.app.ui.logistics.logisticsDetailRoute(de.wanderlogplus.shared.sync.EntityType.FLIGHT, r.id))
                                R.GUIDE -> nav.navigate(de.wanderlogplus.app.ui.guides.guideRoute(r.id))
                                R.REGION -> if (placesViewModelFactory != null) nav.navigate(regionRoute(r.id))
                            }
                        },
                    )
                }
            }
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
                            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                            if (simulatedTime != null && onSimulatedTime != null) {
                                de.wanderlogplus.app.ui.more.SimulationBar(simulatedTime, onSimulatedTime)
                            }
                            TodayScreen(
                                s, now,
                                // A day opened from Heute belongs to the Reise tab: the Heute tab then really leads back.
                                onOpenDay = { d -> nav.navigateToTab(Tab.REISE); nav.navigate(dayRoute(d)) },
                                onOpenTrip = { nav.navigateToTab(Tab.REISE) },
                                onSyncNow = onRefresh,
                                onOpenAttachment = { a -> attachmentActions?.onOpen?.invoke(a) },
                                onOpenLink = onOpenLink,
                                available = attachmentActions?.available ?: { true },
                                onOpenPlace = { id -> nav.navigateToTab(Tab.ORTE); nav.navigate(placeRoute(id)) },
                                onOpenLogistics = { t, id -> nav.navigate(de.wanderlogplus.app.ui.logistics.logisticsDetailRoute(t, id)) },
                                onReplaceTrain = transitApi?.let { { tid: String -> nav.navigate(de.wanderlogplus.app.ui.transit.transitReplaceRoute(tid)) } },
                            )
                            }
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
                                    detail = {
                                        if (date != null) {
                                            de.wanderlogplus.app.ui.chat.ReportChatPane(Tab.REISE.route, "trip/day/{date}", mapOf("date" to date))
                                            // The top bar's chat button already opens the chat for this day.
                                            androidx.compose.runtime.CompositionLocalProvider(de.wanderlogplus.app.ui.design.LocalOpenChat provides null) {
                                                DayDetailFor(vm, s, date, onBack = null)
                                            }
                                        }
                                    },
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
                if (placesViewModelFactory != null) {
                    // A place opened from a day stays in the Reise tab (back leads to the day).
                    composable(TRIP_PLACE_ROUTE) { entry ->
                        val vm: PlacesViewModel = viewModel(factory = placesViewModelFactory)
                        val snap by vm.snapshot.collectAsState()
                        ErrorSnack(vm.error, vm::consumeError, snackbar)
                        val id = entry.arguments?.getString("savedPlaceId").orEmpty()
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            snap?.let { PlaceDetailFor(vm, it, id, onBack = { nav.popBackStack() }) }
                        }
                    }
                }
                composable(DAY_MAP_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val date = entry.arguments?.getString("date").orEmpty()
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s ->
                            DayMapScreen(
                                s, date, onBack = { nav.popBackStack() },
                                onTransitDetails = transitApi?.let { { id -> nav.navigate(de.wanderlogplus.app.ui.transit.transitRoute(date, id)) } },
                            )
                        }
                    }
                }
                composable(de.wanderlogplus.app.ui.transit.TRANSIT_ROUTE) { entry ->
                    val vm: TripViewModel = viewModel(factory = tripViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.REISE.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val date = entry.arguments?.getString("date").orEmpty()
                    val itemId = entry.arguments?.getString("itemId").orEmpty()
                    val api = transitApi
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        val s = snap
                        val way = s?.let { de.wanderlogplus.app.ui.trip.dayWay(it, date, itemId) }?.takeIf { it.from != null && it.to != null }
                        if (way == null || api == null) {
                            EmptyHint("Für diesen Weg fehlt eine Kartenposition.", Modifier.padding(24.dp))
                        } else {
                            val tvm: de.wanderlogplus.app.ui.transit.TransitViewModel = viewModel(
                                key = "transit-$date-$itemId",
                                factory = remember(way) {
                                    object : androidx.lifecycle.ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                                            de.wanderlogplus.app.ui.transit.TransitViewModel(
                                                api,
                                                de.wanderlogplus.shared.sync.TransitPoint(way.from!!.lat, way.from.lng, way.fromName),
                                                de.wanderlogplus.shared.sync.TransitPoint(way.to!!.lat, way.to.lng, way.toName),
                                                date, way.fromTime, way.chosen,
                                            ) as T
                                    }
                                },
                            )
                            val state by tvm.state.collectAsState()
                            de.wanderlogplus.app.ui.transit.TransitScreen(
                                title = "${way.fromName} → ${way.toName}",
                                state = state,
                                accent = MaterialTheme.colorScheme.primary,
                                chosen = way.chosen,
                                onEdit = tvm::edit,
                                onSearch = tvm::search,
                                onChoose = { r -> vm.setLegTransit(date, itemId, r); nav.popBackStack() },
                                onClear = { vm.setLegTransit(date, itemId, null) },
                                onBack = { nav.popBackStack() },
                                suggest = de.wanderlogplus.app.ui.transit.transitSuggest(s, state.form.fromPoint ?: state.form.toPoint, placeFinder ?: geoSearch?.let { de.wanderlogplus.client.PlaceFinder(null, it) }),
                                onPickFrom = tvm::pickFrom, onPickTo = tvm::pickTo, onClearFrom = tvm::clearFrom, onClearTo = tvm::clearTo,
                                onEarlier = tvm::earlier, onLater = tvm::later,
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
                                search = { q, near, bbox -> (placeFinder ?: de.wanderlogplus.client.PlaceFinder(null, geo)).search(q, near, bbox) },
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
                                val researching by vm.researching.collectAsState()
                                val status by vm.researchStatus.collectAsState()
                                val researchJob by vm.researchJob.collectAsState()
                                ResearchSnack(vm, snackbar)
                                PlacesScreen(
                                    s,
                                    onOpenRegion = { nav.navigate(regionRoute(it)) },
                                    onSearch = geoSearch?.let { { nav.navigate(placesSearchRoute(ALL_REGIONS)) } },
                                    onResearchRegions = if (vm.canAssess) vm::researchRegions else null,
                                    researchStatus = status.takeIf { researching.isNotEmpty() },
                                    onShowProgress = researchJob?.takeIf { reelsViewModelFactory != null }?.let { j -> { nav.navigate(de.wanderlogplus.app.ui.reels.jobRoute(j)) } },
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
                                search = { q, near, bbox -> (placeFinder ?: de.wanderlogplus.client.PlaceFinder(null, geo)).search(q, near, bbox) },
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
                                        else {
                                            de.wanderlogplus.app.ui.chat.ReportChatPane(REGION_ROUTE, PLACE_ROUTE, mapOf("savedPlaceId" to id))
                                            PlaceDetailFor(vm, it, id, onBack = null, onOpenSibling = { next -> selectedPlace = next })
                                        }
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
                        snap?.let {
                            PlaceDetailFor(vm, it, id, onBack = { nav.popBackStack() }, onOpenSibling = { next ->
                                nav.navigate(placeRoute(next)) { popUpTo(entry.destination.id) { inclusive = true } }
                            })
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
                            wide -> {
                                var selected by rememberSaveable { mutableStateOf<String?>(null) } // "TYPE/id"
                                val sel = selected?.split('/', limit = 2)?.takeIf { it.size == 2 }
                                TwoPane(
                                    list = {
                                        LogisticsScreen(
                                            s,
                                            onOpen = { e -> selected = "${e.type.name}/${e.id}" },
                                            today = rememberMinuteNow(clock).toLocalDate().toString(),
                                            onAdd = { t -> nav.navigate(logisticsEditRoute(t, de.wanderlogplus.app.ui.logistics.NEW_ID)) },
                                            onAddTransit = { nav.navigate(logisticsEditRoute(EntityType.TRAIN, de.wanderlogplus.app.ui.logistics.NEW_WITH_SEARCH)) },
                                            onOpenJourney = { j -> selected = "JOURNEY/$j" },
                                        )
                                    },
                                    detail = {
                                        if (sel == null) EmptyHint("Fahrt oder Unterkunft in der Liste antippen", Modifier.padding(24.dp))
                                        else androidx.compose.runtime.CompositionLocalProvider(de.wanderlogplus.app.ui.design.LocalOpenChat provides null) {
                                            if (sel[0] == "JOURNEY") JourneyFor(vm, s, sel[1], onBack = null)
                                            else LogisticsDetailFor(vm, s, logisticsTypeOf(sel[0]), sel[1], onBack = null, afterDelete = { selected = null })
                                        }
                                    },
                                )
                            }
                            else -> LogisticsScreen(
                                s,
                                onOpen = { e -> nav.navigate(logisticsDetailRoute(e.type, e.id)) },
                                today = rememberMinuteNow(clock).toLocalDate().toString(),
                                onAdd = { t -> nav.navigate(logisticsEditRoute(t, de.wanderlogplus.app.ui.logistics.NEW_ID)) },
                                            onAddTransit = { nav.navigate(logisticsEditRoute(EntityType.TRAIN, de.wanderlogplus.app.ui.logistics.NEW_WITH_SEARCH)) },
                                onOpenJourney = { j -> nav.navigate(de.wanderlogplus.app.ui.logistics.journeyRoute(j)) },
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
                            if (entry.arguments?.getString("type") == de.wanderlogplus.app.ui.logistics.JOURNEY_SEGMENT) {
                                JourneyFor(vm, s, id, onBack = { nav.popBackStack() })
                            } else {
                                // An unknown type segment gets the same "nicht gefunden" page (with back) as a missing id.
                                LogisticsDetailFor(vm, s, type, id, onBack = { nav.popBackStack() }, afterDelete = { nav.popBackStack() })
                            }
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
                            if (type == EntityType.STAY) {
                                de.wanderlogplus.app.ui.logistics.StayEditScreen(s, id, onBack = { nav.popBackStack() }, onSave = vm::saveStay, search = placeSearch)
                            } else {
                                LogisticsEditScreen(s, type, id, onBack = { nav.popBackStack() }, onSave = vm::saveFields, search = placeSearch, transitApi = transitApi)
                            }
                        }
                    }
                }
                composable(de.wanderlogplus.app.ui.transit.JOURNEY_CHANGE_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.LOGISTIK.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val jid = entry.arguments?.getString("id").orEmpty()
                    val api = transitApi
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        val s = snap
                        val journey = s?.let { de.wanderlogplus.client.journeys(it).firstOrNull { j -> j.id == jid } }
                        if (s == null || journey == null || api == null || !de.wanderlogplus.app.ui.transit.canChangeJourney(journey)) {
                            EmptyHint("Diese Reise lässt sich nicht als Verbindung ändern.", Modifier.padding(24.dp))
                        } else {
                            val tvm: de.wanderlogplus.app.ui.transit.TransitViewModel = viewModel(
                                key = "journey-change-$jid",
                                factory = remember(jid) {
                                    object : androidx.lifecycle.ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                            val c = de.wanderlogplus.app.ui.transit.journeyChangeSeed(journey)
                                            return de.wanderlogplus.app.ui.transit.TransitViewModel(
                                                api, c.seed.from, c.seed.to, c.date, c.time, null, "depart",
                                                fromIsPlace = c.seed.fromIsPlace, toIsPlace = c.seed.toIsPlace,
                                            ).also { v -> v.edit { f -> f.copy(fromStation = c.seed.fromStation, toStation = c.seed.toStation) } } as T
                                        }
                                    }
                                },
                            )
                            val state by tvm.state.collectAsState()
                            de.wanderlogplus.app.ui.transit.TransitScreen(
                                title = "Verbindung ändern: ${journey.first.depart.name} → ${journey.last.arrive.name}",
                                state = state,
                                accent = MaterialTheme.colorScheme.primary,
                                chosen = null,
                                onEdit = tvm::edit,
                                onSearch = tvm::search,
                                onChoose = { r -> vm.replaceJourney(jid, r); nav.popBackStack() },
                                onClear = {},
                                onBack = { nav.popBackStack() },
                                suggest = de.wanderlogplus.app.ui.transit.transitSuggest(s, state.form.fromPoint ?: state.form.toPoint, placeFinder ?: geoSearch?.let { de.wanderlogplus.client.PlaceFinder(null, it) }),
                                onPickFrom = tvm::pickFrom, onPickTo = tvm::pickTo, onClearFrom = tvm::clearFrom, onClearTo = tvm::clearTo,
                                onEarlier = tvm::earlier, onLater = tvm::later,
                            )
                        }
                    }
                }
                composable(de.wanderlogplus.app.ui.transit.TRANSIT_REPLACE_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.LOGISTIK.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val id = entry.arguments?.getString("id").orEmpty()
                    val api = transitApi
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        val s = snap
                        val train = s?.trains?.firstOrNull { it.id == id }
                        if (s == null || train == null || api == null) {
                            EmptyHint("Diese Fahrt gibt es nicht mehr.", Modifier.padding(24.dp))
                        } else {
                            val now = rememberMinuteNow(clock)
                            val tvm: de.wanderlogplus.app.ui.transit.TransitViewModel = viewModel(
                                key = "replace-$id",
                                factory = remember(id) {
                                    object : androidx.lifecycle.ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                            val seed = de.wanderlogplus.app.ui.transit.replaceSeed(train)
                                            val (date, time) = de.wanderlogplus.app.ui.transit.replaceStart(train, now)
                                            return de.wanderlogplus.app.ui.transit.TransitViewModel(api, seed.from, seed.to, date, time, null, "depart", fromIsPlace = seed.fromIsPlace, toIsPlace = seed.toIsPlace).also { v ->
                                                v.edit { f -> f.copy(fromStation = seed.fromStation, toStation = seed.toStation) }
                                            } as T
                                        }
                                    }
                                },
                            )
                            val state by tvm.state.collectAsState()
                            de.wanderlogplus.app.ui.transit.TransitScreen(
                                title = "Andere Fahrt: ${train.depart.name} → ${train.arrive.name}",
                                state = state,
                                accent = MaterialTheme.colorScheme.primary,
                                chosen = null,
                                onEdit = tvm::edit,
                                onSearch = tvm::search,
                                onChoose = { r -> vm.replaceRide(id, r); nav.popBackStack() },
                                onClear = {},
                                onBack = { nav.popBackStack() },
                                suggest = de.wanderlogplus.app.ui.transit.transitSuggest(s, state.form.fromPoint ?: state.form.toPoint, placeFinder ?: geoSearch?.let { de.wanderlogplus.client.PlaceFinder(null, it) }),
                                onPickFrom = tvm::pickFrom, onPickTo = tvm::pickTo, onClearFrom = tvm::clearFrom, onClearTo = tvm::clearTo,
                                onEarlier = tvm::earlier, onLater = tvm::later,
                            )
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
                                onImport = { picks, tips -> vm.import(id, picks, tips, job?.source) },
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
                                    search = { q, near, bbox -> (placeFinder ?: de.wanderlogplus.client.PlaceFinder(null, geo)).search(q, near, bbox) },
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
                composable(Tab.BUDGET.route) {
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    val s = snap
                    when {
                        s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        s.isEmpty -> EmptyState(onSyncNow = onRefresh)
                        budgetVm == null -> EmptyHint("Das Budget ist hier nicht verfügbar", Modifier.padding(24.dp))
                        else -> de.wanderlogplus.app.ui.budget.BudgetScreen(s, rememberMinuteNow(clock).toLocalDate().toString(), budgetVm)
                    }
                }
                composable(Tab.MEHR.route) {
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory)
                    val snap by vm.snapshot.collectAsState()
                    SyncedScreen(syncStatus, onRefresh) {
                        MoreScreen(snap?.conflicts?.size ?: 0, onOpen = { nav.navigate(it) }, vaultScan = vaultScanViewModelFactory != null && hasAdminToken)
                    }
                }
                if (vaultScanViewModelFactory != null) {
                    composable(de.wanderlogplus.app.ui.vault.VAULT_ROUTE) {
                        val vm: de.wanderlogplus.app.ui.vault.VaultScanViewModel = viewModel(factory = vaultScanViewModelFactory)
                        val scanState by vm.state.collectAsState()
                        val found by vm.found.collectAsState()
                        val pending by vm.pending.collectAsState()
                        SyncedScreen(syncStatus, onRefresh) {
                            de.wanderlogplus.app.ui.vault.VaultScanScreen(
                                state = scanState,
                                found = found,
                                pending = pending,
                                onResume = vm::resume,
                                webUrl = settingsInfo.serverUrl?.let { url ->
                                    settingsInfo.token?.let { token -> "${url.trimEnd('/')}/vault?token=$token" }
                                },
                                onStart = vm::start,
                            )
                        }
                    }
                }
                composable(de.wanderlogplus.app.ui.guides.GUIDES_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.MEHR.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    LaunchedEffect(Unit) { guidesVm?.refreshJobs() }
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s ->
                            de.wanderlogplus.app.ui.guides.GuidesScreen(
                                s, guidesVm, guideJobs,
                                onOpen = { id -> nav.navigate(de.wanderlogplus.app.ui.guides.guideRoute(id)) },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
                composable(de.wanderlogplus.app.ui.guides.GUIDE_ROUTE) { entry ->
                    val vm: MoreViewModel = viewModel(factory = moreViewModelFactory, viewModelStoreOwner = nav.entryOrNull(Tab.MEHR.route) ?: entry)
                    val snap by vm.snapshot.collectAsState()
                    val id = entry.arguments?.getString("guideId")?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    LaunchedEffect(id) { guidesVm?.refreshJobs() }
                    SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                        snap?.let { s ->
                            de.wanderlogplus.app.ui.guides.GuideScreen(
                                s, id, guidesVm, jobs = guideJobs,
                                onBack = { nav.popBackStack() },
                                onOpenGuide = { other -> nav.navigate(de.wanderlogplus.app.ui.guides.guideRoute(other)) },
                                onImprove = openChat,
                            )
                        }
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
                if (accessViewModelFactory != null) {
                    composable(de.wanderlogplus.app.ui.more.ACCESS_ROUTE) {
                        val vm: de.wanderlogplus.app.ui.more.AccessViewModel = viewModel(factory = accessViewModelFactory)
                        val state by vm.state.collectAsState()
                        SyncedScreen(syncStatus, onRefresh, scrollable = false) {
                            de.wanderlogplus.app.ui.more.AccessScreen(
                                state = state,
                                serverUrl = currentServer?.url,
                                onSaveAdminToken = vm::setAdminToken,
                                onForgetAdminToken = vm::forgetAdminToken,
                                onCreate = vm::create,
                                onDelete = vm::delete,
                                onDismissCreated = vm::dismissCreated,
                                onBack = { nav.popBackStack() },
                            )
                        }
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
                            appVersion = appVersion,
                            onCheckUpdates = onCheckUpdates,
                            debugMode = debugMode,
                            onDebugMode = onDebugMode,
                            server = currentServer,
                            onOpenAccess = accessViewModelFactory?.let { { nav.navigate(de.wanderlogplus.app.ui.more.ACCESS_ROUTE) } },
                            simulatedTime = simulatedTime,
                            onSimulatedTime = onSimulatedTime,
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
                composable(de.wanderlogplus.app.ui.attachments.PHOTOS_ROUTE) { entry ->
                    val type = entry.arguments?.getString("type")?.let { t -> EntityType.entries.firstOrNull { it.name == t } }
                    val targetId = entry.arguments?.getString("targetId").orEmpty()
                    val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
                    val photos = remember(snapshot, type, targetId) { type?.let { snapshot?.photosFor(it, targetId) }.orEmpty() }
                    de.wanderlogplus.app.ui.attachments.PhotoViewerScreen(
                        photos, index, attachmentFiles,
                        onDelete = attachmentActions?.onDelete,
                        onBack = { nav.popBackStack() },
                    )
                }
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
                    !((it == Tab.LOGISTIK || it == Tab.BUDGET || it == Tab.MEHR) && moreViewModelFactory != null)
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
    }
}

/** Routes whose screen has no photo header; they show [de.wanderlogplus.app.ui.chat.ChatTopBar] instead. */
private val CHAT_BAR_ROUTES = setOf(Tab.REISE.route, Tab.ORTE.route, Tab.LOGISTIK.route, Tab.BUDGET.route, Tab.MEHR.route)

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

/** Place detail opened from a day's program (Reise tab). */
const val TRIP_PLACE_ROUTE = "trip/place/{savedPlaceId}"

fun tripPlaceRoute(savedPlaceId: String) = "trip/place/${android.net.Uri.encode(savedPlaceId)}"

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

/** The chat cards' buttons, written through the trip's editor; [notify] confirms each one in a snackbar. */
private fun chatActions(vm: TripViewModel, snapshot: () -> TripSnapshot?, notify: (String) -> Unit) = object : de.wanderlogplus.app.ui.chat.ChatActions {
    override fun planSaved(savedPlaceId: String, date: String, position: ItemPosition) {
        vm.addSuggestionAt(date, savedPlaceId, position)
        notify("Eingeplant am " + dayCardLabel(date))
    }

    override fun planFound(card: de.wanderlogplus.shared.sync.ChatCard, date: String, position: ItemPosition) {
        val lat = card.lat ?: return
        val lng = card.lng ?: return
        vm.addFoundToDay(date, position, card.title, lat, lng, card.address, card.placeCategory())
        notify("Eingeplant am " + dayCardLabel(date) + " und zu Orten hinzugefügt")
    }

    override fun addFound(card: de.wanderlogplus.shared.sync.ChatCard) {
        val lat = card.lat ?: return
        val lng = card.lng ?: return
        val region = card.regionId?.let { id -> snapshot()?.regions?.firstOrNull { it.id == id } }
        vm.addFoundToPlaces(
            card.title, lat, lng, card.address, card.placeCategory(), region?.id,
            note = card.url?.let { "Gefunden im Reise-Chat: $it" }.orEmpty(),
            googlePlaceId = card.googlePlaceId,
        )
        notify("Zu Orten hinzugefügt" + (region?.let { " (${it.name})" } ?: ""))
    }

    override fun applyPlan(date: String, ops: List<de.wanderlogplus.client.PlanOp>, remove: Set<String>, at: Long) {
        vm.applyPlan(date, ops, remove, at)
        notify("Plan für " + dayCardLabel(date) + " übernommen – im Verlauf des Tages wiederherstellbar")
    }

    override fun restorePlan(date: String, at: Long) {
        vm.restoreVersion(date, at)
        notify("Programm von " + dayCardLabel(date) + " zurückgesetzt")
    }
}
