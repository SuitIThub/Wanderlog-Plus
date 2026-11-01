package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.applyChanges
import de.wanderlogplus.shared.sync.parse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncResult {
    data class Ok(val pushed: Int, val pulled: Int) : SyncResult
    data class Failed(val kind: ApiErrorKind, val message: String) : SyncResult
}

class SyncEngine(
    private val api: SyncApi,
    private val store: EntityStore,
    private val outbox: Outbox,
    private val state: SyncStateStore,
    private val clock: HlcClock,
    private val tx: Transactor = NoTransaction,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private class Counts(var pushed: Int = 0, var pulled: Int = 0)

    suspend fun sync(): SyncResult = mutex.withLock {
        try {
            val health = api.health()
            val known = state.epoch
            if (known == null) state.epoch = health.epoch
            else if (known != health.epoch) {
                resetCursorAndOutbox()
                state.epoch = health.epoch
            }
            val counts = Counts()
            try {
                pushAll(counts)
                pullAll(counts)
            } catch (e: ApiException) {
                if (e.kind != ApiErrorKind.CURSOR_AHEAD) throw e
                resetCursorAndOutbox()
                pushAll(counts)
                pullAll(counts)
            }
            state.lastSyncAt = now()
            state.lastError = null
            SyncResult.Ok(counts.pushed, counts.pulled)
        } catch (e: ApiException) {
            val message = messageFor(e.kind)
            state.lastError = message
            SyncResult.Failed(e.kind, message)
        }
    }

    /** Same as an epoch change, but keeps the stored epoch: the whole local field state is re-pushed. */
    fun resetLocalCursor() = resetCursorAndOutbox()

    private fun resetCursorAndOutbox() = tx.run {
        state.since = 0
        // The store already holds every pending local change, so the full state supersedes the queue.
        while (true) {
            val pending = outbox.peek(RESET_DRAIN)
            if (pending.isEmpty()) break
            outbox.remove(pending.map { it.seq })
        }
        outbox.add(localFieldState())
    }

    /**
     * Fields written by devices. Importer/server-written fields are skipped: the server rejects those node ids
     * on push and regenerates them itself.
     */
    private fun localFieldState(): List<FieldChange> = store.all().flatMap { r ->
        r.fields.mapNotNull { (field, fv) ->
            if (field == "_source" || field == "_sourceId" || field.startsWith("_base.")) return@mapNotNull null
            val node = Hlc.parse(fv.hlc).nodeId
            if (node == "importer" || node == "server") return@mapNotNull null
            FieldChange(r.type, r.id, field, fv.value, fv.hlc)
        }
    }

    private suspend fun pushAll(counts: Counts) {
        while (true) {
            val block = outbox.peek(BLOCK).toMutableList()
            if (block.isEmpty()) return
            var attempts = 0
            val maxAttempts = block.size
            while (block.isNotEmpty()) {
                try {
                    val response = api.push(block.map { it.change })
                    response.maxHlc?.let { clock.receive(Hlc.parse(it)) }
                    outbox.remove(block.map { it.seq })
                    counts.pushed += block.size
                    break
                } catch (e: ApiException) {
                    val n = rejectedIndex(e)
                    if (n == null || n < 1 || n > block.size || attempts >= maxAttempts) throw e
                    attempts++
                    outbox.remove(listOf(block.removeAt(n - 1).seq))
                }
            }
        }
    }

    private fun rejectedIndex(e: ApiException): Int? {
        if (e.kind != ApiErrorKind.BAD_REQUEST || e.code !in REJECT_CODES) return null
        return CHANGE_NO.find(e.message ?: return null)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun pullAll(counts: Counts) {
        do {
            val page = api.pull(state.since, BLOCK)
            tx.run {
                for (record in page.records) {
                    val changes = record.fields.map { (field, fv) -> FieldChange(record.type, record.id, field, fv.value, fv.hlc) }
                    store.applyChanges(changes)
                    changes.forEach { clock.receive(Hlc.parse(it.hlc)) }
                }
                state.since = page.nextSeq
            }
            counts.pulled += page.records.size
        } while (page.hasMore)
    }

    private fun messageFor(kind: ApiErrorKind) = when (kind) {
        ApiErrorKind.NETWORK -> "Server nicht erreichbar"
        ApiErrorKind.UNAUTHORIZED -> "Token ungültig"
        ApiErrorKind.PROTOCOL -> "App-Version passt nicht zum Server"
        ApiErrorKind.CLOCK_DRIFT -> "Uhrzeit des Geräts weicht stark ab"
        ApiErrorKind.CURSOR_AHEAD -> "Sync-Zustand passt nicht zum Server"
        ApiErrorKind.BAD_REQUEST -> "Server hat Änderungen abgelehnt"
        ApiErrorKind.SERVER -> "Serverfehler"
        ApiErrorKind.NOT_CONFIGURED -> "Import nicht konfiguriert"
    }

    private companion object {
        const val BLOCK = 500
        const val RESET_DRAIN = 1000
        val REJECT_CODES = setOf("invalid_change", "invalid_hlc", "reserved_field", "reserved_node")
        val CHANGE_NO = Regex("""change #(\d+)""")
    }
}
