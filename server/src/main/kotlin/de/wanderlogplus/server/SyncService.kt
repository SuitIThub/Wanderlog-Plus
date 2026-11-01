package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.PullResponse
import de.wanderlogplus.shared.sync.PushResponse
import de.wanderlogplus.shared.sync.applyChanges
import de.wanderlogplus.shared.sync.encode
import de.wanderlogplus.shared.sync.parse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

const val MAX_CLOCK_DRIFT_MILLIS = 86_400_000L
const val MAX_PUSH_CHANGES = 5000
const val MAX_PUSH_COUNTER = 999_000
const val IMPORTER_NODE = "importer"
const val SERVER_NODE = "server"

/** A push batch was refused as a whole; [message] is fixed text and never contains request data. */
class PushRejected(val code: String, message: String) : RuntimeException(message)

/** Our own request validation failed (query parameters, multipart parts); maps to 400 `bad_request` without a message. */
class BadInput : RuntimeException()

/** The client's pull cursor is beyond the server's sequence (database was recreated or restored); maps to 409. */
class CursorAhead : RuntimeException()

class SyncService(
    private val store: SqliteStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Shared write lock: pushes and imports must not interleave. */
    val lock: Any = Any()

    private val clock = HlcClock(SERVER_NODE, now = now)

    fun <T> withWriteLock(block: () -> T): T = synchronized(lock) { block() }

    fun pull(since: Long, limit: Int): PullResponse {
        if (since < 0 || limit !in 1..1000) throw BadInput()
        if (since > store.maxSeq()) throw CursorAhead()
        return store.pull(since, limit)
    }

    fun push(author: String, changes: List<FieldChange>): PushResponse {
        validate(changes)
        return withWriteLock {
            store.transaction(author) {
                val max = MaxHlc()
                val accepted = apply(changes, max)
                PushResponse(accepted, store.maxSeq(), max.value?.encode(), store.epoch)
            }
        }
    }

    private fun validate(changes: List<FieldChange>) {
        if (changes.size > MAX_PUSH_CHANGES) throw PushRejected("too_many_changes", "at most $MAX_PUSH_CHANGES changes per push")
        val limit = now() + MAX_CLOCK_DRIFT_MILLIS
        changes.forEachIndexed { i, c ->
            val n = i + 1
            if (c.id.isEmpty() || c.field.isEmpty()) throw PushRejected("invalid_change", "change #$n: empty id or field")
            val h = try {
                Hlc.parse(c.hlc)
            } catch (e: IllegalArgumentException) {
                throw PushRejected("invalid_hlc", "change #$n: invalid HLC")
            }
            if (h.counter >= MAX_PUSH_COUNTER) throw PushRejected("invalid_hlc", "change #$n: invalid HLC")
            if (c.field == "_source" || c.field == "_sourceId" || c.field.startsWith("_base.")) {
                throw PushRejected("reserved_field", "change #$n: reserved field")
            }
            if (h.wallMillis > limit) throw PushRejected("clock_drift", "change #$n: HLC is too far in the future")
            if (h.nodeId == IMPORTER_NODE || h.nodeId == SERVER_NODE) throw PushRejected("reserved_node", "change #$n: reserved node id")
        }
    }

    /** Tracks the highest HLC among fields actually written during one push. */
    private inner class MaxHlc {
        var value: Hlc? = null

        fun write(changes: List<FieldChange>): List<FieldChange> {
            val accepted = store.applyChanges(changes)
            for (c in accepted) {
                val h = Hlc.parse(c.hlc)
                if (value.let { it == null || h > it }) value = h
            }
            return accepted
        }
    }

    private fun isEdit(field: String) = !field.startsWith("_") && field != "deleted"

    /** Keeps only the highest-HLC change per (type, id, field), in original order. */
    private fun collapse(changes: List<FieldChange>): List<FieldChange> {
        val best = HashMap<Triple<EntityType, String, String>, Int>()
        changes.forEachIndexed { i, c ->
            val key = Triple(c.type, c.id, c.field)
            val j = best[key]
            if (j == null || Hlc.parse(c.hlc) > Hlc.parse(changes[j].hlc)) best[key] = i
        }
        return changes.filterIndexed { i, c -> best[Triple(c.type, c.id, c.field)] == i }
    }

    /** Applies the validated batch inside the caller's transaction; returns the number of device changes written. */
    private fun apply(batch: List<FieldChange>, max: MaxHlc): Int {
        val changes = collapse(batch)
        val deletedInBatch = changes.filter { it.field == "deleted" }.map { it.type to it.id }.toSet()
        var accepted = 0
        val edited = LinkedHashSet<Pair<EntityType, String>>()
        for (c in changes) {
            val record = store.get(c.type, c.id)
            val stored = record?.fields?.get(c.field)
            val wrote = if (stored != null && isImporterConflict(c, record, stored)) {
                writeConflict(c, stored.hlc, stored.value, max)
                true
            } else {
                max.write(listOf(c)).isNotEmpty()
            }
            if (wrote) {
                accepted++
                if (isEdit(c.field)) edited += c.type to c.id
            }
        }
        for ((type, id) in edited) if ((type to id) !in deletedInBatch) reviveIfDeletedByImport(type, id, max)
        return accepted
    }

    private fun isImporterConflict(c: FieldChange, record: EntityRecord, stored: FieldValue): Boolean {
        if (!isEdit(c.field) || c.value == stored.value) return false
        val s = Hlc.parse(stored.hlc)
        if (s.nodeId != IMPORTER_NODE || Hlc.parse(c.hlc) >= s) return false
        return record.fields.containsKey("_base.${c.field}")
    }

    private fun writeConflict(c: FieldChange, storedHlc: String, storedValue: JsonElement, max: MaxHlc) {
        clock.receive(Hlc.parse(storedHlc))
        catchUp(c.type, c.id, "_conflict.${c.field}")
        // The winning field gets the later tick, so maxHlc of the push equals the winner's stored HLC.
        val conflict = FieldChange(c.type, c.id, "_conflict.${c.field}", storedValue, clock.tick().encode())
        val winner = FieldChange(c.type, c.id, c.field, c.value, clock.tick().encode())
        max.write(listOf(winner, conflict))
    }

    /** Moves the server clock past an existing value of [field], so a generated write is strictly newer than it. */
    private fun catchUp(type: EntityType, id: String, field: String) {
        store.get(type, id)?.fields?.get(field)?.let { clock.receive(Hlc.parse(it.hlc)) }
    }

    private fun reviveIfDeletedByImport(type: EntityType, id: String, max: MaxHlc) {
        val record: EntityRecord = store.get(type, id) ?: return
        val deleted = record.fields["deleted"] ?: return
        if (!record.isDeleted || Hlc.parse(deleted.hlc).nodeId != IMPORTER_NODE) return
        clock.receive(Hlc.parse(deleted.hlc))
        catchUp(type, id, "_removedInSource")
        max.write(
            listOf(
                FieldChange(type, id, "deleted", JsonPrimitive(false), clock.tick().encode()),
                FieldChange(type, id, "_removedInSource", JsonPrimitive(true), clock.tick().encode()),
            ),
        )
    }
}
