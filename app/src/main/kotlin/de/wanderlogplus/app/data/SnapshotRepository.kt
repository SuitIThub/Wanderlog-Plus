package de.wanderlogplus.app.data

import androidx.room.InvalidationTracker
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.EntityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Rebuilds the [TripSnapshot] whenever the `fields` table changes; null only until the first load. */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotRepository(
    private val store: EntityStore,
    private val db: AppDatabase,
    scope: CoroutineScope,
) {
    /** Emits once initially and after every change to the fields table. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer("fields") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        trySend(Unit)
        db.invalidationTracker.addObserver(observer)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }

    val snapshot: StateFlow<TripSnapshot?> = changes()
        .conflate()
        .mapLatest { TripSnapshot.from(store.all()) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)
}
