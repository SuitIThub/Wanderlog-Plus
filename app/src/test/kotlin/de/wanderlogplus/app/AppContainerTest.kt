package de.wanderlogplus.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.wanderlogplus.app.data.FieldEntity
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import de.wanderlogplus.shared.sync.parse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {
    @Test fun readyClockIsAheadOfStoredHlc() = runBlocking {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Context>())
        try {
            val future = Hlc(System.currentTimeMillis() + 3_600_000, 5, "other").encode()
            withContext(Dispatchers.IO) {
                container.db.dao().upsert(listOf(FieldEntity("TRIP", "t", "name", "\"x\"", future)))
            }
            val ready = withTimeout(20_000) { container.ready.await() }
            val next = ready.clock.tick()
            assertTrue(next > Hlc.parse(future))
            assertSame(ready.editor, container.editor())
        } finally {
            container.scope.cancel()
            withContext(Dispatchers.IO) { container.db.close() }
        }
    }
}
