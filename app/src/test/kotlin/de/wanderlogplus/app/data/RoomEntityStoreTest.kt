package de.wanderlogplus.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomEntityStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomEntityStore

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        store = RoomEntityStore(db)
    }

    @After fun tearDown() = db.close()

    @Test fun roundtripWithJsonNullAndEmoji() {
        val rec = EntityRecord(
            EntityType.PLACE, "p1",
            mapOf(
                "name" to FieldValue(JsonPrimitive("Café 🍜 \"Ü\""), "000000000000001-000000-a"),
                "note" to FieldValue(JsonNull, "000000000000002-000000-a"),
            ),
        )
        store.put(rec)
        assertEquals(rec, store.get(EntityType.PLACE, "p1"))
        assertNull(store.get(EntityType.PLACE, "missing"))
        store.put(EntityRecord(EntityType.TRIP, "t1", mapOf("name" to FieldValue(JsonPrimitive("x"), "000000000000003-000000-a"))))
        assertEquals(2, store.all().size)
        assertEquals(listOf(rec), store.all(EntityType.PLACE))
    }

    @Test fun applyChangesDiscardsOlderHlc() {
        val newer = FieldChange(EntityType.TRIP, "t", "name", JsonPrimitive("new"), "000000000000010-000000-a")
        val older = FieldChange(EntityType.TRIP, "t", "name", JsonPrimitive("old"), "000000000000005-000000-b")
        assertEquals(listOf(newer), store.applyChanges(listOf(newer)))
        assertTrue(store.applyChanges(listOf(older)).isEmpty())
        assertEquals(JsonPrimitive("new"), store.get(EntityType.TRIP, "t")!!.value("name"))
    }
}
