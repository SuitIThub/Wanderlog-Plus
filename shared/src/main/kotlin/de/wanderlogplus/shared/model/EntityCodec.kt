package de.wanderlogplus.shared.model

import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.util.UUID

object EntityCodec {
    @PublishedApi
    internal val decodeJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @PublishedApi
    internal val encodeJson = Json { encodeDefaults = true }

    @PublishedApi
    internal fun isReserved(field: String) = field == "deleted" || field.startsWith("_")

    @PublishedApi
    internal fun toObject(record: EntityRecord): JsonObject = JsonObject(
        buildMap {
            record.fields.forEach { (k, v) -> if (!isReserved(k) && k != "id") put(k, v.value) }
            put("id", JsonPrimitive(record.id))
        },
    )

    inline fun <reified T> decode(record: EntityRecord): T =
        decodeJson.decodeFromJsonElement(serializer<T>(), toObject(record))

    inline fun <reified T> fields(entity: T): Map<String, JsonElement> {
        val obj = encodeJson.encodeToJsonElement(serializer<T>(), entity) as JsonObject
        return obj.filterKeys { it != "id" }
    }

    fun importedId(type: EntityType, sourceId: String): String =
        UUID.nameUUIDFromBytes("wanderlog:${type.name}:$sourceId".toByteArray()).toString()
}
