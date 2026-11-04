package de.wanderlogplus.app

import android.content.Context
import androidx.room.Room
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.MIGRATION_1_2
import de.wanderlogplus.app.data.PendingUploadEntity
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.files.FileSyncRunner
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import de.wanderlogplus.app.data.RoomEntityStore
import de.wanderlogplus.app.data.RoomOutbox
import de.wanderlogplus.app.data.SettingsStore
import de.wanderlogplus.app.data.SnapshotRepository
import de.wanderlogplus.app.data.roomTransactor
import de.wanderlogplus.app.sync.SyncRunner
import de.wanderlogplus.app.sync.SyncScheduler
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.client.GeoSearch
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.parse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Ids
import kotlinx.serialization.json.JsonPrimitive

/** Everything that needs disk access to initialize. */
class Ready(val clock: HlcClock, val editor: LocalEditor, val snapshots: SnapshotRepository)

/**
 * Manual DI. Construction is cheap and never touches disk. Disk-dependent pieces are built in [ready],
 * which runs on Dispatchers.IO once started ([warmUp]); consumers await it instead of touching the DB directly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, AppProfile.databaseName(appContext))
            .addMigrations(MIGRATION_1_2)
            .build()
    }
    val store: RoomEntityStore by lazy { RoomEntityStore(db) }
    val outbox: RoomOutbox by lazy { RoomOutbox(db) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }
    val syncRunner: SyncRunner by lazy { SyncRunner(this) }
    val http: HttpClient by lazy { HttpClient(OkHttp) }
    /** Place search for stopovers; a third-party service, so it never gets the server token. */
    val geoSearch: GeoSearch by lazy { GeoSearch(http) }

    val attachmentFiles: AttachmentFiles by lazy { AttachmentFiles(File(appContext.filesDir, AppProfile.scoped(appContext, "attachments"))) }

    private var apiFor: ServerSettings? = null
    private var api: SyncApi? = null

    /** The API for the currently configured server (reused while the configuration is unchanged); null without a server. */
    @Synchronized
    private fun currentApi(): SyncApi? {
        val server = runBlocking { settings.server.first() } ?: return null
        api?.let { if (apiFor == server) return it }
        return SyncApi(http, server.url, server.token).also { api = it; apiFor = server }
    }

    /** API for the current server (null without one); for callers on coroutines. */
    suspend fun syncApi(): SyncApi? = withContext(Dispatchers.IO) { currentApi() }

    /** Ways between a day's places (cached on disk, so legs seen once work offline). */
    val legs: LegRepository by lazy { LegRepository(api = { syncApi() }, dir = File(appContext.filesDir, AppProfile.scoped(appContext, "legs"))) }

    val fileSyncRunner: FileSyncRunner by lazy {
        FileSyncRunner(
            api = { currentApi() },
            files = attachmentFiles,
            uploads = db.pendingUploads(),
            attachments = { TripSnapshot.from(store.all()).attachments },
        )
    }

    /**
     * The clock is advanced past the highest stored HLC so local writes always order after existing data.
     * Started lazily so tests can seed the DB first; [warmUp] starts it.
     */
    val ready: Deferred<Ready> = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        val clock = HlcClock(nodeId = requireNotNull(settings.nodeId))
        db.dao().maxHlc()?.let { clock.receive(Hlc.parse(it)) }
        val editor = LocalEditor(store, outbox, clock, db.roomTransactor, onChange = { syncScheduler.requestSoon(); syncRunner.refreshAsync() })
        Ready(clock, editor, SnapshotRepository(store, db, scope))
    }

    suspend fun editor(): LocalEditor = ready.await().editor
    suspend fun snapshots(): SnapshotRepository = ready.await().snapshots

    private val writeLock = Mutex()

    /**
     * The single write path: runs [block] on Dispatchers.IO under one app-wide lock with a snapshot built
     * inside the lock, so concurrent actions never see a stale view of each other's changes.
     */
    suspend fun <T> edit(block: (LocalEditor, TripSnapshot) -> T): T {
        val editor = editor()
        return writeLock.withLock {
            withContext(Dispatchers.IO) { block(editor, TripSnapshot.from(store.all())) }
        }
    }

    /**
     * Stores the file locally, queues its upload and creates the attachment record right away (also offline).
     * Other devices show the attachment as not available offline until the file has been uploaded.
     */
    suspend fun addAttachment(
        targetType: EntityType,
        targetId: String,
        input: InputStream,
        fileName: String,
        mimeType: String,
    ): String = addAttachment(targetType, { targetId }, input, fileName, mimeType)

    /** Like above, but [targetId] is resolved only after the file was imported (a rejected file creates nothing). */
    suspend fun addAttachment(
        targetType: EntityType,
        targetId: suspend () -> String,
        input: InputStream,
        fileName: String,
        mimeType: String,
    ): String {
        val imported = withContext(Dispatchers.IO) { attachmentFiles.importStream(input) }
        val resolvedTarget = targetId()
        val id = edit { e, _ -> e.addAttachment(targetType, resolvedTarget, imported.hash, fileName, mimeType, imported.size) }
        // Queued only once the record exists, so a failed target or edit leaves no orphan upload.
        withContext(Dispatchers.IO) {
            db.pendingUploads().insert(PendingUploadEntity(imported.hash, fileName, mimeType, System.currentTimeMillis()))
        }
        syncScheduler.requestFiles()
        return id
    }

    /** The id of the Day for [date]; the Day (empty title) is created when missing. One edit, so no duplicates. */
    suspend fun ensureDay(date: String): String = edit { e, s ->
        s.queries()?.dayFor(date)?.id ?: run {
            e.setDayFields(s, date, mapOf("title" to JsonPrimitive("")))
            Ids.dayId(requireNotNull(s.trip).id, date)
        }
    }

    fun warmUp() {
        ready.start()
    }
}
