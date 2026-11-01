package de.wanderlogplus.importer

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldChange
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.parse
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
        /** Record types whose `placeId` field keeps a PLACE alive. */
        val REFERRERS = setOf(EntityType.SAVED_PLACE, EntityType.STAY, EntityType.DAY_ITEM)
    }

    fun merge(snapshot: ImportSnapshot, store: EntityStore): ImportResult {
        // Observe stored HLCs so our writes are strictly newer than anything we may overwrite
        // (otherwise EntityRecord.apply silently drops them, possibly only half of a pair).
        store.all().asSequence().flatMap { it.fields.values.asSequence() }
            .maxOfOrNull { Hlc.parse(it.hlc) }?.let { clock.receive(it) }

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
            if (!rec.isFromWanderlog()) {
                if (rec.fields.containsKey("_source")) continue // owned by another source
                // Adopt a device-recreated record (devices re-pushed to a fresh server before the import).
                val hadContent = rec.fields.keys.any { !it.startsWith("_") && it != "deleted" }
                var changed = false
                set(entity.type, entity.id, "_source", JsonPrimitive(SOURCE))
                set(entity.type, entity.id, "_sourceId", JsonPrimitive(entity.sourceId))
                if (!rec.fields.containsKey("deleted")) {
                    set(entity.type, entity.id, "deleted", FALSE)
                    set(entity.type, entity.id, "_base.deleted", FALSE)
                } else if (!rec.fields.containsKey("_base.deleted")) {
                    set(entity.type, entity.id, "_base.deleted", rec.get("deleted"))
                }
                for ((f, w) in entity.fields) {
                    val present = rec.fields.containsKey(f)
                    val master = f in entity.masterFields
                    when {
                        f in entity.createOnlyFields -> if (!present) {
                            set(entity.type, entity.id, f, w)
                            if (!master) set(entity.type, entity.id, "_base.$f", w)
                            changed = true
                        }
                        !present -> {
                            set(entity.type, entity.id, f, w)
                            if (!master) set(entity.type, entity.id, "_base.$f", w)
                            changed = true
                        }
                        master -> if (rec.get(f) != w) {
                            set(entity.type, entity.id, f, w)
                            changed = true
                        }
                        else -> if (rec.get("_base.$f") != w) {
                            // keep the device value (treated as an app edit), remember what Wanderlog said
                            set(entity.type, entity.id, "_base.$f", w)
                            changed = true
                        }
                    }
                }
                if (hadContent) updated++ else created++
                continue
            }

            // Snapshot implicitly says deleted = false for every contained entity.
            val fields = entity.fields + ("deleted" to FALSE)
            var entityUpdated = false
            for ((f, w) in fields) {
                if (f in entity.createOnlyFields) continue
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

        // Decide the non-place records first, so a place can tell which referrers survive this very pass.
        val missing = store.all().filter { it.isFromWanderlog() && (it.type to it.id) !in seen && !it.isDeleted }
        val toDelete = missing.filter { it.type != EntityType.PLACE && !it.isEdited() }.map { it.type to it.id }.toSet()
        val referenced = HashSet<String>()
        for (rec in store.all()) {
            if (rec.type !in REFERRERS || rec.isDeleted || (rec.type to rec.id) in toDelete) continue
            (rec.get("placeId") as? JsonPrimitive)?.takeIf { it.isString }?.let { referenced += it.content }
        }

        for (rec in missing) {
            val delete = if (rec.type == EntityType.PLACE) rec.id !in referenced && !rec.isEdited() else (rec.type to rec.id) in toDelete
            if (delete) {
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

    /** Nutzfelder with a base are the non-master snapshot fields; master fields never carry a base. */
    private fun EntityRecord.isEdited(): Boolean {
        val nutzfelder = fields.keys.filter { !it.startsWith("_") && it != "deleted" && "_base.$it" in fields } + "deleted"
        return nutzfelder.any { get(it) != get("_base.$it") }
    }

    private fun EntityRecord.isFromWanderlog() = get("_source") == JsonPrimitive(SOURCE)
}
