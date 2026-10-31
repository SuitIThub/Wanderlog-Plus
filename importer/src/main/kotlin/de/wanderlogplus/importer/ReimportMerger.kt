package de.wanderlogplus.importer

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.change
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class ImportStats(val created: Int, val updated: Int, val conflicts: Int, val removed: Int, val flaggedRemoved: Int)

data class ImportResult(val changes: List<FieldChange>, val stats: ImportStats)

/**
 * Three-way merge of a Wanderlog snapshot (W) against the stored state (A) and the
 * last imported value (B = `_base.<field>`). Only reads from the store; the caller applies the changes.
 */
class ReimportMerger(private val clock: HlcClock) {
    private companion object {
        const val SOURCE = "wanderlog"
        val FALSE: JsonElement = JsonPrimitive(false)
        val TRUE: JsonElement = JsonPrimitive(true)
    }

    fun merge(snapshot: ImportSnapshot, store: EntityStore): ImportResult {
        val changes = ArrayList<FieldChange>()
        var created = 0
        var updated = 0
        var conflicts = 0
        var removed = 0
        var flagged = 0

        fun set(type: EntityType, id: String, field: String, value: JsonElement) {
            changes += clock.change(type, id, field, value)
        }

        val seen = HashSet<Pair<EntityType, String>>()
        for (entity in snapshot.entities) {
            seen += entity.type to entity.id
            val rec = store.get(entity.type, entity.id)
            if (rec == null) {
                created++
                set(entity.type, entity.id, "_source", JsonPrimitive(SOURCE))
                set(entity.type, entity.id, "_sourceId", JsonPrimitive(entity.sourceId))
                set(entity.type, entity.id, "deleted", FALSE)
                set(entity.type, entity.id, "_base.deleted", FALSE)
                for ((f, w) in entity.fields) {
                    set(entity.type, entity.id, f, w)
                    if (f !in entity.masterFields) set(entity.type, entity.id, "_base.$f", w)
                }
                continue
            }
            if (!rec.isFromWanderlog()) continue

            // Snapshot implicitly says deleted = false for every contained entity.
            val fields = entity.fields + ("deleted" to FALSE)
            var entityUpdated = false
            for ((f, w) in fields) {
                val a = rec.get(f)
                if (f in entity.masterFields) {
                    if (a != w) {
                        set(entity.type, entity.id, f, w)
                        entityUpdated = true
                    }
                    continue
                }
                val b = rec.get("_base.$f")
                when {
                    a == b -> if (w != b) {
                        set(entity.type, entity.id, f, w)
                        set(entity.type, entity.id, "_base.$f", w)
                        entityUpdated = true
                    }
                    w == b -> Unit // app edit, wanderlog unchanged
                    a != w -> {
                        // both sides changed differently; stays quiet on re-run because base == w afterwards
                        set(entity.type, entity.id, "_conflict.$f", w)
                        set(entity.type, entity.id, "_base.$f", w)
                        conflicts++
                    }
                    else -> { // same change on both sides
                        set(entity.type, entity.id, "_base.$f", w)
                        if (rec.get("_conflict.$f") != JsonNull) set(entity.type, entity.id, "_conflict.$f", JsonNull)
                    }
                }
            }
            if (entityUpdated) updated++
            if (rec.get("_removedInSource") == TRUE) set(entity.type, entity.id, "_removedInSource", FALSE)
        }

        for (rec in store.all()) {
            if (!rec.isFromWanderlog() || (rec.type to rec.id) in seen || rec.isDeleted) continue
            // Nutzfelder with a base are the non-master snapshot fields; master fields never carry a base.
            val nutzfelder = rec.fields.keys.filter { !it.startsWith("_") && it != "deleted" && "_base.$it" in rec.fields } + "deleted"
            val edited = nutzfelder.any { rec.get(it) != rec.get("_base.$it") }
            if (!edited) {
                set(rec.type, rec.id, "deleted", TRUE)
                set(rec.type, rec.id, "_base.deleted", TRUE)
                removed++
            } else {
                if (rec.get("_removedInSource") != TRUE) set(rec.type, rec.id, "_removedInSource", TRUE)
                flagged++
            }
        }

        return ImportResult(changes, ImportStats(created, updated, conflicts, removed, flagged))
    }

    private fun EntityRecord.get(field: String): JsonElement = fields[field]?.value ?: JsonNull

    private fun EntityRecord.isFromWanderlog() = get("_source") == JsonPrimitive(SOURCE)
}
