package de.wanderlogplus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Blocking methods; callers must not use them on the main thread. */
@Dao
interface PendingUploadDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(upload: PendingUploadEntity)

    @Query("SELECT * FROM pending_upload ORDER BY createdAt, hash")
    fun all(): List<PendingUploadEntity>

    @Query("DELETE FROM pending_upload WHERE hash = :hash")
    fun delete(hash: String)
}
