package de.wanderlogplus.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.shared.sync.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class SnapshotRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomEntityStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = RoomEntityStore(db)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun tripName() = FieldChange(EntityType.TRIP, "t1", "name", JsonPrimitive("Japan"), "000000000000010-000000-a")

    @Test fun emitsNewSnapshotAfterWrite() = runBlocking {
        val repo = SnapshotRepository(store, db, scope)
        withTimeout(10_000) { assertNotNull(repo.snapshot.first { it != null }) }
        io { store.applyChanges(listOf(tripName())) }
        val s = withTimeout(10_000) { repo.snapshot.first { it?.trip?.id == "t1" } }
        assertEquals("t1", s!!.trip!!.id)
    }

    @Test fun badRowsDoNotCrashSnapshot() = runBlocking {
        io {
            store.applyChanges(listOf(tripName()))
            db.dao().upsert(
                listOf(
                    FieldEntity("NOT_A_TYPE", "x", "f", "1", "000000000000011-000000-a"),
                    FieldEntity("NOTE", "n1", "text", "{garbage", "000000000000012-000000-a"),
                ),
            )
        }
        val repo = SnapshotRepository(store, db, scope)
        val s = withTimeout(10_000) { repo.snapshot.first { it != null } }
        assertEquals("t1", s!!.trip!!.id)
        assertNull(repo.error.value)
    }
}
