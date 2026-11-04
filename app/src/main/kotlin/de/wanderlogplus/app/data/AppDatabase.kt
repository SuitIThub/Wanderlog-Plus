package de.wanderlogplus.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FieldEntity::class, OutboxEntity::class, PendingUploadEntity::class, VaultFoundEntity::class, VaultScanStateEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FieldDao
    abstract fun pendingUploads(): PendingUploadDao
    abstract fun vault(): VaultDao
}
