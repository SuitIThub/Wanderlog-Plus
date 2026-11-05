package de.wanderlogplus.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One media image the classifier flagged; [uploadedAt] is null until the file reached the server. */
@Entity(tableName = "vault_found")
data class VaultFoundEntity(
    @PrimaryKey val uri: String,
    val fileName: String,
    val mimeType: String,
    val score: Double,
    val foundAt: Long,
    val uploadedAt: Long?,
)

/** The last scan's progress (single row, [id] is always 1) so the UI works without the server. */
@Entity(tableName = "vault_scan_state")
data class VaultScanStateEntity(
    @PrimaryKey val id: Int,
    /** running | done | failed (see de.wanderlogplus.shared.sync.VaultScanStates). */
    val state: String,
    val total: Int,
    val scanned: Int,
    val found: Int,
    val updatedAt: Long,
    /** Diagnostics: every image MediaStore exposes, regardless of format ([total] is the scoreable subset). */
    val galleryTotal: Int = 0,
    /** Legacy diagnostics from the classifier era (always 0 since the vault uploads everything). */
    val scored: Int = 0,
    val decodeFailed: Int = 0,
    /** Highest classifier score of the whole run (0..1) – shows how far below the threshold everything was. */
    val maxScore: Double = 0.0,
)

/** Blocking methods unless they return a Flow; callers must not use them on the main thread. */
@Dao
interface VaultDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertFound(item: VaultFoundEntity): Long

    @Query("SELECT uri FROM vault_found")
    fun foundUris(): List<String>

    @Query("SELECT * FROM vault_found WHERE uploadedAt IS NULL ORDER BY foundAt, uri")
    fun pendingUploads(): List<VaultFoundEntity>

    @Query("SELECT COUNT(*) FROM vault_found WHERE uploadedAt IS NULL")
    fun pendingUploadCount(): Int

    @Query("UPDATE vault_found SET uploadedAt = :at WHERE uri = :uri")
    fun markUploaded(uri: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertScanState(state: VaultScanStateEntity)

    @Query("SELECT * FROM vault_scan_state WHERE id = 1")
    fun scanState(): VaultScanStateEntity?

    @Query("SELECT * FROM vault_scan_state WHERE id = 1")
    fun observeScanState(): Flow<VaultScanStateEntity?>

    @Query("SELECT COUNT(*) FROM vault_found")
    fun observeFoundCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vault_found WHERE uploadedAt IS NULL")
    fun observePendingCount(): Flow<Int>
}
