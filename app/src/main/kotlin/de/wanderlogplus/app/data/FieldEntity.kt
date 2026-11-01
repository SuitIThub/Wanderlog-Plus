package de.wanderlogplus.app.data

import androidx.room.Entity

/** One field of an entity; [value] is the JSON-encoded value, [hlc] its encoded timestamp. */
@Entity(tableName = "fields", primaryKeys = ["type", "id", "field"])
data class FieldEntity(
    val type: String,
    val id: String,
    val field: String,
    val value: String,
    val hlc: String,
)
