package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

enum class EntityType { TRIP, REGION, PLACE, SAVED_PLACE, STAY, FLIGHT, TRAIN, DAY, DAY_ITEM, NOTE, ATTACHMENT, EXPENSE, GUIDE, GUIDE_PREFS, VOTE }

/** A field value with the [Hlc.encode]d timestamp of its last write. */
@Serializable
data class FieldValue(val value: JsonElement, val hlc: String)

@Serializable
data class FieldChange(
    val type: EntityType,
    val id: String,
    val field: String,
    val value: JsonElement,
    val hlc: String,
)

@Serializable
data class EntityRecord(
    val type: EntityType,
    val id: String,
    val fields: Map<String, FieldValue> = emptyMap(),
) {
    /** Value of [field]; JSON null and missing both yield null. */
    fun value(field: String): JsonElement? = fields[field]?.value?.takeIf { it !is JsonNull }

    val isDeleted: Boolean
        get() = (value("deleted") as? JsonPrimitive)?.booleanOrNull == true

    /** Returns the updated record, or null if [change] is not strictly newer than the stored field HLC. */
    fun apply(change: FieldChange): EntityRecord? {
        require(change.type == type && change.id == id) { "Change targets ${change.type}/${change.id}, not $type/$id" }
        val incoming = Hlc.parse(change.hlc)
        val existing = fields[change.field]
        if (existing != null && incoming <= Hlc.parse(existing.hlc)) return null
        return copy(fields = fields + (change.field to FieldValue(change.value, change.hlc)))
    }
}
