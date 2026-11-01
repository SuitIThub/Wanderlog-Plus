package de.wanderlogplus.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A locally stored attachment file that still has to be uploaded to the server. */
@Entity(tableName = "pending_upload")
data class PendingUploadEntity(
    @PrimaryKey val hash: String,
    val fileName: String,
    val mimeType: String,
    val createdAt: Long,
)
