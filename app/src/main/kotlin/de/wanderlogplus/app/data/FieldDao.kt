package de.wanderlogplus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Blocking methods; callers must not use them on the main thread. */
@Dao
interface FieldDao {
    @Query("SELECT * FROM fields WHERE type = :type AND id = :id")
    fun get(type: String, id: String): List<FieldEntity>

    @Query("SELECT * FROM fields WHERE type = :type ORDER BY id, field")
    fun allOfType(type: String): List<FieldEntity>

    @Query("SELECT * FROM fields ORDER BY type, id, field")
    fun all(): List<FieldEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(fields: List<FieldEntity>)

    @Query("SELECT MAX(hlc) FROM fields")
    fun maxHlc(): String?

    @Query("SELECT COUNT(*) FROM fields")
    fun observeFieldCount(): Flow<Int>

    @Insert
    fun insert(entries: List<OutboxEntity>)

    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    fun peek(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE seq IN (:seqs)")
    fun delete(seqs: List<Long>)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Int
}
