package de.wanderlogplus.app.data

import androidx.room.InvalidationTracker
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.sync.EntityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rebuilds the [TripSnapshot] whenever the `fields` table changes; null only until the first load.
 * A failed rebuild keeps the last good snapshot and sets [error] (exception class name only, never data).
 */
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
        db.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }

    private val _snapshot = MutableStateFlow<TripSnapshot?>(null)
    val snapshot: StateFlow<TripSnapshot?> = _snapshot

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        scope.launch {
            changes().conflate().collectLatest {
                withContext(Dispatchers.Default) {
                    try {
                        _snapshot.value = TripSnapshot.from(store.all())
                        _error.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _error.value = e.javaClass.simpleName
                    }
                }
            }
        }
    }
}
