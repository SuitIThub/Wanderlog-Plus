package de.wanderlogplus.app.sync

import de.wanderlogplus.app.AppContainer
import de.wanderlogplus.app.data.ServerSettings
import de.wanderlogplus.app.data.roomTransactor
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.client.SyncEngine
import de.wanderlogplus.client.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncStatus(
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val pending: Int = 0,
)

/**
 * Runs syncs for WorkManager and the UI. One [HttpClient] and one [SyncEngine] per server configuration are
 * reused so the engine's mutex serializes concurrent syncs.
 */
class SyncRunner(private val container: AppContainer) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status

    private var engineFor: ServerSettings? = null
    private var engine: SyncEngine? = null

    /** Reloads persisted state and the outbox size. Disk access happens on IO. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val pending = container.outbox.size()
        _status.update {
            it.copy(
                pending = pending,
                lastSyncAt = container.settings.lastSyncAt,
                lastError = if (it.running) it.lastError else container.settings.lastError,
            )
        }
    }

    fun refreshAsync() {
        container.scope.launch { try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* status stays as is */ } }
    }

    /** Null when no server is configured. */
    suspend fun runOnce(): SyncResult? = withContext(Dispatchers.IO) {
        val server = container.settings.server.first() ?: return@withContext null
        val ready = container.ready.await()
        val engine = engineFor(server, ready.clock)
        _status.update { it.copy(running = true) }
        try {
            val result = engine.sync()
            _status.update { it.copy(lastError = (result as? SyncResult.Failed)?.message) }
            result
        } finally {
            _status.update { it.copy(running = false) }
            refreshAsync()
        }
    }

    @Synchronized
    private fun engineFor(server: ServerSettings, clock: de.wanderlogplus.shared.sync.HlcClock): SyncEngine {
        engine?.let { if (engineFor == server) return it }
        val created = SyncEngine(
            api = SyncApi(container.http, server.url, server.token),
            store = container.store,
            outbox = container.outbox,
            state = container.settings,
            clock = clock,
            tx = container.db.roomTransactor,
        )
        engineFor = server
        engine = created
        return created
    }
}
