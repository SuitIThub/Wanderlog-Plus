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
}
