package de.wanderlogplus.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.shared.sync.*
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
class RoomOutboxTest {
    private lateinit var db: AppDatabase
    private lateinit var outbox: RoomOutbox

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        outbox = RoomOutbox(db)
    }

    @After fun tearDown() = db.close()

    private fun ch(n: Int) = FieldChange(EntityType.NOTE, "n$n", "text", JsonPrimitive("v$n 🙂"), "00000000000000$n-000000-a")

    @Test fun addPeekRemoveSize() {
        outbox.add(listOf(ch(1), ch(2), ch(3)))
        assertEquals(3, outbox.size())
        val all = outbox.peek(10)
        assertEquals(listOf(ch(1), ch(2), ch(3)), all.map { it.change })
        assertEquals(all.map { it.seq }.sorted(), all.map { it.seq })
        assertEquals(2, outbox.peek(2).size)
        outbox.remove(listOf(all[1].seq))
        assertEquals(listOf(ch(1), ch(3)), outbox.peek(10).map { it.change })
        assertEquals(2, outbox.size())
        outbox.remove(emptyList())
        assertEquals(2, outbox.size())
    }
}
