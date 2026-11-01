package de.wanderlogplus.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val type: String,
    val id: String,
    val field: String,
    val value: String,
    val hlc: String,
)
