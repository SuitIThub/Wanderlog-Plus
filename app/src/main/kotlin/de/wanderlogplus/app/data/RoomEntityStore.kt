package de.wanderlogplus.app.data

import de.wanderlogplus.client.Transactor
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun encodeJson(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)
internal fun decodeJson(s: String): JsonElement = Json.parseToJsonElement(s)

/** [EntityStore] on Room. Blocking: call off the main thread (or inside a transaction). */
class RoomEntityStore(private val db: AppDatabase) : EntityStore {
    private val dao get() = db.dao()

    override fun get(type: EntityType, id: String): EntityRecord? =
        dao.get(type.name, id).takeIf { it.isNotEmpty() }?.let { toRecords(it).single() }

    override fun put(record: EntityRecord) {
        if (record.fields.isEmpty()) return
        dao.upsert(
            record.fields.map { (field, fv) ->
                FieldEntity(record.type.name, record.id, field, encodeJson(fv.value), fv.hlc)
            },
        )
    }

    override fun all(type: EntityType): List<EntityRecord> = toRecords(dao.allOfType(type.name))

    override fun all(): List<EntityRecord> = toRecords(dao.all())

    private fun toRecords(rows: List<FieldEntity>): List<EntityRecord> =
        rows.mapNotNull { row ->
            // Rows of an unknown type or with undecodable JSON are skipped rather than crashing readers.
            val type = EntityType.entries.firstOrNull { it.name == row.type } ?: return@mapNotNull null
            val value = try {
                decodeJson(row.value)
            } catch (_: SerializationException) {
                return@mapNotNull null
            }
            Triple(type to row.id, row.field, FieldValue(value, row.hlc))
        }.groupBy({ it.first }, { it.second to it.third }).map { (key, fs) ->
            EntityRecord(key.first, key.second, fs.toMap())
        }
}

val AppDatabase.roomTransactor: Transactor
    get() = object : Transactor {
        override fun <T> run(block: () -> T): T = runInTransaction<T>(block)
    }
