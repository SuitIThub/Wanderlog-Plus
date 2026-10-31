package de.wanderlogplus.shared.sync

import kotlinx.serialization.json.JsonElement

interface EntityStore {
    fun get(type: EntityType, id: String): EntityRecord?
    fun put(record: EntityRecord)
    fun all(type: EntityType): List<EntityRecord>
    fun all(): List<EntityRecord>
}

class InMemoryEntityStore : EntityStore {
    private val records = LinkedHashMap<Pair<EntityType, String>, EntityRecord>()

    override fun get(type: EntityType, id: String): EntityRecord? = records[type to id]
    override fun put(record: EntityRecord) { records[record.type to record.id] = record }
    override fun all(type: EntityType): List<EntityRecord> = records.values.filter { it.type == type }
    override fun all(): List<EntityRecord> = records.values.toList()
}

/** Applies [changes] in order; returns those actually accepted (newer than the stored field). */
fun EntityStore.applyChanges(changes: List<FieldChange>): List<FieldChange> {
    val accepted = ArrayList<FieldChange>()
    for (c in changes) {
        val base = get(c.type, c.id) ?: EntityRecord(c.type, c.id)
        val updated = base.apply(c) ?: continue
        put(updated)
        accepted += c
    }
    return accepted
}

fun HlcClock.change(type: EntityType, id: String, field: String, value: JsonElement): FieldChange =
    FieldChange(type, id, field, value, tick().encode())
