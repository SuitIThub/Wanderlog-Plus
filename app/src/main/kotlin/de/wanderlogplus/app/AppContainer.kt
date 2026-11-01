package de.wanderlogplus.app

import android.content.Context
import androidx.room.Room
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.RoomEntityStore
import de.wanderlogplus.app.data.RoomOutbox
import de.wanderlogplus.app.data.SettingsStore
import de.wanderlogplus.app.data.SnapshotRepository
import de.wanderlogplus.app.data.roomTransactor
import de.wanderlogplus.app.sync.SyncRunner
import de.wanderlogplus.app.sync.SyncScheduler
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
        Room.databaseBuilder(appContext, AppDatabase::class.java, "wanderlog-plus.db").build()
    }
    val store: RoomEntityStore by lazy { RoomEntityStore(db) }
    val outbox: RoomOutbox by lazy { RoomOutbox(db) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val syncScheduler: SyncScheduler by lazy { SyncScheduler(appContext) }
    val syncRunner: SyncRunner by lazy { SyncRunner(this) }
    val http: HttpClient by lazy { HttpClient(OkHttp) }

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

    fun warmUp() {
        ready.start()
    }
}
