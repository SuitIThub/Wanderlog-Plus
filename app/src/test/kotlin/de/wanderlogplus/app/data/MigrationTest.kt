package de.wanderlogplus.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To2KeepsFieldsAndOutbox() {
        helper.createDatabase("mig", 1).apply {
            execSQL("INSERT INTO fields (type, id, field, value, hlc) VALUES ('TRIP','t','name','\"x\"','h1')")
            execSQL("INSERT INTO outbox (type, id, field, value, hlc) VALUES ('TRIP','t','name','\"x\"','h1')")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 2, true, MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM fields").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM pending_upload").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    @Test fun migrate4To5KeepsRowsAndAddsVideoColumns() {
        helper.createDatabase("mig", 4).apply {
            execSQL("INSERT INTO vault_found (uri, fileName, mimeType, score, foundAt, uploadedAt) VALUES ('content://img/1','a.jpg','image/jpeg',0.0,7,NULL)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 5, true, MIGRATION_4_5)
        db.query("SELECT kind, durationMs, sizeBytes FROM vault_found WHERE uri = 'content://img/1'").use {
            it.moveToFirst()
            assertEquals("photo", it.getString(0))
            assertEquals(true, it.isNull(1))
            assertEquals(true, it.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM vault_video_requests").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
}
