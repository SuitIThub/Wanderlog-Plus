package de.wanderlogplus.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import de.wanderlogplus.app.vault.VaultMediaAccess
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.data.TripZone
import de.wanderlogplus.app.data.now
import de.wanderlogplus.app.maps.LocalMapsEnabled
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.maps.LocalRouteMapRenderer
import de.wanderlogplus.app.maps.RouteMapRenderer
import androidx.compose.runtime.CompositionLocalProvider
import de.wanderlogplus.app.ui.attachments.AttachmentsViewModel
import de.wanderlogplus.app.ui.setup.SetupViewModel
import de.wanderlogplus.app.ui.places.PlacesViewModel
import de.wanderlogplus.app.ui.trip.TripViewModel
import de.wanderlogplus.client.TripSnapshot
import androidx.compose.runtime.rememberCoroutineScope
import de.wanderlogplus.app.ui.more.ImportViewModel
import de.wanderlogplus.app.ui.reels.ReelsViewModel
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
    private val pendingReel = MutableStateFlow<String?>(null)

    // launchMode="singleTop": a new wlp:// link reaches the running activity via onNewIntent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSetupIntent(intent)
        handleReelIntent(intent)
    }

    private fun handleReelIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_REEL_JOB)?.takeIf { it.isNotBlank() }?.let { pendingReel.value = it }
    }

    private fun handleSetupIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        SetupLink.parse(raw)?.let { pendingSetup.value = it }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** On grant the first vault scan starts right away (the daily schedule already exists from the Application). */
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val sdk = Build.VERSION.SDK_INT
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        if (VaultMediaAccess.canReadImages(sdk, ::granted)) {
            (application as WanderlogPlusApp).container.syncScheduler.requestVaultScan()
        }
    }

    /** Asks for POST_NOTIFICATIONS (API 33+) only once, so a refusal is never nagged about. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(de.wanderlogplus.app.AppProfile.scoped(this, "reminders"), MODE_PRIVATE)
        if (prefs.getBoolean("permissionAsked", false)) return
        prefs.edit().putBoolean("permissionAsked", true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Asks once for the media access the vault scan needs (the background worker itself cannot show the dialog).
     * The photo picker used elsewhere needs no permission, so nothing else in the app ever asks.
     */
    private fun requestMediaPermissionOnce() {
        val sdk = Build.VERSION.SDK_INT
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val access = VaultMediaAccess
        if (access.canReadImages(sdk, ::granted) && access.canReadVideos(sdk, ::granted)) return
        val missing = access.missing(sdk, ::granted)
        if (missing.isEmpty()) return
        val prefs = getSharedPreferences(de.wanderlogplus.app.AppProfile.scoped(this, "vault"), MODE_PRIVATE)
        // A previous version asked only for photos; upgrades must still ask for video once.
        val flag = if (access.canReadImages(sdk, ::granted)) "permissionAskedVideo" else "permissionAsked"
        if (prefs.getBoolean(flag, false)) return
        prefs.edit().putBoolean(flag, true).apply()
        mediaPermission.launch(missing)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as WanderlogPlusApp).container
        if (savedInstanceState == null) {
            handleSetupIntent(intent)
            handleReelIntent(intent)
        }
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
                val remindersEnabled by remember { container.settings.remindersEnabled }.collectAsState(true)
                val hasAdminToken by remember { container.settings.adminTokenSet }.collectAsState(false)
                val assessConfig by remember { container.settings.assessConfig }.collectAsState(de.wanderlogplus.shared.sync.AssessConfig())
                val debugMode = de.wanderlogplus.app.AppProfile.isDebug(this@MainActivity)
                val simulated by remember { container.settings.simulatedTime }.collectAsState(null)
                // Debug mode may pretend another day for "Heute"; everything else keeps the real time.
                val appClock: () -> java.time.LocalDateTime = remember(tripZone, simulated, debugMode) {
                    val sim = simulated?.takeIf { debugMode }
                    val clockFn: () -> java.time.LocalDateTime = { tripZone.now().let { real -> sim?.at(real) ?: real } }
                    clockFn
                }
                // Not on the very first launch before Setup: ask once a server is configured.
                LaunchedEffect(remindersEnabled, hasServer) { if (remindersEnabled && hasServer == true) requestNotificationPermissionOnce() }
                // Same gate for the vault scan: without media access it silently no-ops, so ask once up front.
                LaunchedEffect(hasServer) { if (hasServer == true) requestMediaPermissionOnce() }
                val status by container.syncRunner.status.collectAsState()
                LaunchedEffect(Unit) { container.syncRunner.refresh() }
                if (loadFailed) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Lokale Daten konnten nicht geladen werden", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    }
                    return@WanderlogPlusTheme
                }
                val pending by pendingSetup.collectAsState()
                val reelJob by pendingReel.collectAsState()
                // Real maps only here: MapLibre is initialized lazily on first use, never in tests (they keep the defaults).
                val routeMaps = remember { RouteMapRenderer(applicationContext) }
                val updates: de.wanderlogplus.app.update.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = remember { updatesFactory(container) })
                val updateState by updates.state.collectAsState()
                var waitingInstall by remember { mutableStateOf<java.io.File?>(null) }
                LaunchedEffect(Unit) { updates.checkAuto() }
                // Back from "Installieren aus dieser Quelle erlauben": continue with the install.
                androidx.lifecycle.compose.LifecycleResumeEffect(waitingInstall) {
                    val apk = waitingInstall
                    if (apk != null && packageManager.canRequestPackageInstalls()) {
                        waitingInstall = null
                        de.wanderlogplus.app.update.installApk(this@MainActivity, apk)
                    }
                    onPauseOrDispose { }
                }
                val tripWeather by container.weather.weather.collectAsState()
                remember {
                    de.wanderlogplus.app.ui.design.HeaderImages.resolver = { ref ->
                        when {
                            ref.startsWith(de.wanderlogplus.app.ui.design.HeaderImages.OWN) ->
                                container.attachmentFiles.fileFor(ref.removePrefix(de.wanderlogplus.app.ui.design.HeaderImages.OWN))?.toURI()?.toString()
                            ref.startsWith(de.wanderlogplus.app.ui.design.HeaderImages.GOOGLE) ->
                                container.placePhotos.cachedFile(ref.removePrefix(de.wanderlogplus.app.ui.design.HeaderImages.GOOGLE), de.wanderlogplus.app.ui.places.PlacePhotoRepository.FULL_WIDTH)?.toURI()?.toString()
                            else -> null
                        }
                    }
                    true
                }
                val flightLive: suspend (String) -> de.wanderlogplus.shared.sync.FlightLive? = remember { { id -> container.syncApi()?.flightLive(id) } }
                val trainLive: suspend (String) -> de.wanderlogplus.shared.sync.TrainLive? = remember { { id -> container.syncApi()?.trainLive(id) } }
                // The repository asks the server only when its weather is older than 3 hours.
                LaunchedEffect(hasServer) {
                    if (hasServer == true) while (true) {
                        container.weather.refresh()
                        kotlinx.coroutines.delay(15 * 60_000L)
                    }
                }
                CompositionLocalProvider(
                    LocalMapsEnabled provides true, LocalRouteMapRenderer provides routeMaps, LocalLegRepository provides container.legs,
                    de.wanderlogplus.app.ui.weather.LocalTripWeather provides tripWeather,
                    de.wanderlogplus.app.ui.places.LocalPlacePhotos provides container.placePhotos,
                    de.wanderlogplus.app.ui.logistics.LocalFlightLive provides flightLive,
                    de.wanderlogplus.app.ui.logistics.LocalTrainLive provides trainLive,
                ) {
                    AppNavigation(
                        pendingSetup = pending,
                        onPendingSetupConsumed = { pendingSetup.value = null },
                        reelsViewModelFactory = remember { reelsFactory(container) },
                        chatViewModelFactory = remember { chatFactory(container) },
                        budgetViewModelFactory = remember { budgetFactory(container) },
                        guidesViewModelFactory = remember { guidesFactory(container) },
                        stayInfoViewModelFactory = remember { stayInfoFactory(container) },
                        vaultScanViewModelFactory = remember { vaultScanFactory(container) },
                        hasAdminToken = hasAdminToken,
                        pendingReelJob = reelJob,
                        onPendingReelConsumed = { pendingReel.value = null },
                        hasServer = hasServer,
                        placeSearch = { q, near -> container.geoSearch.search(q, near) },
                        geoSearch = container.geoSearch,
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
                        remindersEnabled = remindersEnabled,
                        onRemindersEnabled = { v -> scope.launch { container.settings.setRemindersEnabled(v) } },
                        assessConfig = assessConfig,
                        onAssessConfig = { c -> scope.launch { container.settings.setAssessConfig(c) } },
                        clock = appClock,
                        simulatedTime = simulated?.takeIf { debugMode },
                        onSimulatedTime = if (debugMode) { s -> scope.launch { container.settings.setSimulatedTime(s) } } else null,
                        currentServer = server,
                        accessViewModelFactory = remember { accessFactory(container) },
                        attachmentsViewModelFactory = remember { attachmentsFactory(container) },
                        attachmentFiles = container.attachmentFiles,
                        onReloadMapImages = routeMaps::clearCache,
                        appVersion = updates.version,
                        transitApi = { container.syncApi() },
                        debugMode = de.wanderlogplus.app.AppProfile.isDebug(this@MainActivity),
                        onDebugMode = { on -> de.wanderlogplus.app.AppProfile.switchTo(this@MainActivity, on) },
                        onCheckUpdates = updates::checkNow,
                        onResetSync = {
                            scope.launch {
                                container.syncRunner.resetCursor()
                                container.syncScheduler.requestNow()
                            }
                        },
                    )
                    if (de.wanderlogplus.app.AppProfile.isDebug(this@MainActivity)) {
                        de.wanderlogplus.app.ui.more.DebugMarker(server?.url)
                    }
                    de.wanderlogplus.app.update.UpdateDialog(
                        state = updateState,
                        currentVersion = updates.version,
                        debugBuild = isDebugBuild,
                        onUpdate = updates::download,
                        onLater = updates::later,
                        onSkip = updates::skip,
                        onCancelDownload = updates::cancelDownload,
                        onInstall = { ready ->
                            updates.dismiss()
                            if (!de.wanderlogplus.app.update.installApk(this@MainActivity, ready.apk)) waitingInstall = ready.apk
                        },
                        onDismiss = updates::dismiss,
                    )
                }
            }
        }
    }

    private val isDebugBuild: Boolean
        get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun updatesFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = de.wanderlogplus.app.update.UpdateViewModel(
            source = de.wanderlogplus.app.update.ReleaseSource(container.http),
            currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0",
            prefs = PrefsUpdatePrefs(applicationContext),
            downloadDir = java.io.File(cacheDir, "updates"),
            autoCheck = !isDebugBuild,
        ) as T
    }

    private fun accessFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = de.wanderlogplus.app.ui.more.AccessViewModel(
            adminApi = { t -> container.adminApi(t) },
            loadToken = { container.settings.adminToken() },
            saveToken = { t -> container.settings.setAdminToken(t) },
        ) as T
    }

    private fun stayInfoFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = de.wanderlogplus.app.ui.logistics.StayInfoViewModel(
            container.scope,
            start = { id, links, fresh -> container.syncApi()?.startStayResearch(id, links, fresh) },
            job = { id -> container.syncApi()?.aiJob(id) },
            syncNow = container.syncScheduler::requestNow,
        ) as T
    }

    private fun guidesFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = de.wanderlogplus.app.ui.guides.GuidesViewModel(
            { block -> container.edit(block) }, container.scope,
            startGuides = { asks -> container.syncApi()?.startGuides(asks) },
            job = { id -> container.syncApi()?.aiJob(id) },
        ) as T
    }

    private fun budgetFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = de.wanderlogplus.app.ui.budget.BudgetViewModel(
            { block -> container.edit(block) }, container.scope, { container.syncApi() },
            today = { kotlinx.coroutines.runBlocking { container.settings.tripZone.first() }.now().toLocalDate().toString() },
        ) as T
    }

    private fun chatFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = de.wanderlogplus.app.ui.chat.ChatViewModel(
            de.wanderlogplus.app.ui.chat.SyncApiChatBackend { container.syncApi() },
            PrefsLastChat(applicationContext),
            locator = de.wanderlogplus.app.ui.chat.AndroidChatLocator(applicationContext),
        ) as T
    }

    private fun reelsFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReelsViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, { container.syncApi() }, PrefsImportedJobs(applicationContext), container.scope, config = { container.settings.assessConfig.first() }) as T
    }

    private fun tripFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TripViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, container.scope) as T
    }

    private fun placesFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlacesViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, container.scope, api = { container.syncApi() }, config = { container.settings.assessConfig.first() }, syncNow = container.syncScheduler::requestNow) as T
    }

    private fun moreFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MoreViewModel({ container.snapshots().snapshot }, { block -> container.edit(block) }, container.scope) as T
    }

    private fun vaultScanFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            de.wanderlogplus.app.ui.vault.VaultScanViewModel(
                scanState = { container.db.vault().observeScanState() },
                foundCount = { container.db.vault().observeFoundCount() },
                pendingCount = { container.db.vault().observePendingCount() },
                startScan = container.syncScheduler::requestVaultScan,
                resumeUploads = container.syncScheduler::requestVaultUploads,
            ) as T
    }

    private fun attachmentsFactory(container: AppContainer) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AttachmentsViewModel(
            edit = { block -> container.edit(block) },
            addAttachment = { type, id, input, name, mime -> container.addAttachment(type, id, input, name, mime) },
            ensureDay = container::ensureDay,
            writeScope = container.scope,
            addPhoto = { type, id, input, name -> container.addPhoto(type, id, input, name) },
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
            return SetupViewModel(tester, cur?.url.orEmpty(), cur?.token.orEmpty(), savedServer = { container.settings.server.first() }) { url, token ->
                container.settings.saveServer(ServerSettings(url, token))
                container.syncScheduler.schedulePeriodic()
                container.syncScheduler.requestNow()
            } as T
        }
    }
}

/** Intent extra with the id of a reel job started from the share sheet. */
const val EXTRA_REEL_JOB = "de.wanderlogplus.app.REEL_JOB"

/** Imported reel jobs in SharedPreferences (per device; the places themselves sync). */
/** When the app last looked for an update and which version the user skipped (per device). */
class PrefsUpdatePrefs(context: android.content.Context) : de.wanderlogplus.app.update.UpdatePrefs {
    private val prefs = context.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE)
    override var lastCheckAt: Long
        get() = prefs.getLong("lastCheckAt", 0L)
        set(v) { prefs.edit().putLong("lastCheckAt", v).apply() }
    override var skippedVersion: String?
        get() = prefs.getString("skipped", null)
        set(v) { prefs.edit().putString("skipped", v).apply() }
}

/** The chat that was open last (per profile). */
class PrefsLastChat(context: android.content.Context) : de.wanderlogplus.app.ui.chat.LastChat {
    private val prefs = context.getSharedPreferences(de.wanderlogplus.app.AppProfile.scoped(context, "chat"), android.content.Context.MODE_PRIVATE)
    override suspend fun get(): String? = prefs.getString("last", null)
    override suspend fun set(id: String?) {
        prefs.edit().apply { if (id == null) remove("last") else putString("last", id) }.apply()
    }
}

class PrefsImportedJobs(context: android.content.Context) : de.wanderlogplus.app.ui.reels.ImportedJobs {
    private val prefs = context.getSharedPreferences(de.wanderlogplus.app.AppProfile.scoped(context, "reels"), android.content.Context.MODE_PRIVATE)
    override fun contains(id: String) = id in prefs.getStringSet("imported", emptySet()).orEmpty()
    override fun add(id: String) {
        val set = prefs.getStringSet("imported", emptySet()).orEmpty() + id
        prefs.edit().putStringSet("imported", set).apply()
    }
}
