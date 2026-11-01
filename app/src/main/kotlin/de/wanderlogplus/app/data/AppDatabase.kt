package de.wanderlogplus.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [FieldEntity::class, OutboxEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FieldDao
}
