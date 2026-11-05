package de.wanderlogplus.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_upload` (`hash` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`hash`))")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `vault_found` (`uri` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `score` REAL NOT NULL, `foundAt` INTEGER NOT NULL, `uploadedAt` INTEGER, PRIMARY KEY(`uri`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `vault_scan_state` (`id` INTEGER NOT NULL, `state` TEXT NOT NULL, `total` INTEGER NOT NULL, `scanned` INTEGER NOT NULL, `found` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `vault_scan_state` ADD COLUMN `galleryTotal` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `vault_scan_state` ADD COLUMN `scored` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `vault_scan_state` ADD COLUMN `decodeFailed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `vault_scan_state` ADD COLUMN `maxScore` REAL NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Existing rows are plain photo backups; video metadata arrives with newly scanned rows.
        db.execSQL("ALTER TABLE `vault_found` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'photo'")
        db.execSQL("ALTER TABLE `vault_found` ADD COLUMN `durationMs` INTEGER")
        db.execSQL("ALTER TABLE `vault_found` ADD COLUMN `sizeBytes` INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS `vault_video_requests` (`ref` TEXT NOT NULL, `requestedAt` INTEGER NOT NULL, PRIMARY KEY(`ref`))")
    }
}
