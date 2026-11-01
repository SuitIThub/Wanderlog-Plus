package de.wanderlogplus.app

import android.content.Context
import androidx.room.Room
import de.wanderlogplus.app.data.AppDatabase
import de.wanderlogplus.app.data.RoomEntityStore
import de.wanderlogplus.app.data.RoomOutbox
import de.wanderlogplus.app.data.SettingsStore
import de.wanderlogplus.app.data.SnapshotRepository
import de.wanderlogplus.app.data.roomTransactor
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.parse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI. Everything that touches disk is lazy; [warmUp] initializes it on a background dispatcher
 * so the main thread normally never pays for it.
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

    /** Advanced past the highest stored HLC so local writes always order after existing data. */
    val clock: HlcClock by lazy {
        HlcClock(nodeId = requireNotNull(settings.nodeId)).also { c ->
            db.dao().maxHlc()?.let { c.receive(Hlc.parse(it)) }
        }
    }

    /** Hook for the sync scheduler (Task 6). */
    var onLocalChange: () -> Unit = {}

    val editor: LocalEditor by lazy {
        LocalEditor(store, outbox, clock, db.roomTransactor, onChange = { onLocalChange() })
    }
    val snapshots: SnapshotRepository by lazy { SnapshotRepository(store, db, scope) }

    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            clock
            editor
            snapshots
        }
    }
}
