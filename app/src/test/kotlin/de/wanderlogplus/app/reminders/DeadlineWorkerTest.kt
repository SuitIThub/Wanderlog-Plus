package de.wanderlogplus.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeadlineWorkerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val nm get() = shadowOf(ctx.getSystemService(NotificationManager::class.java))

    private val snapshot = TripSnapshot(
        trip = null, regions = emptyList(),
        places = mapOf("p" to Place(id = "p", name = "Hotel Sakura")),
        savedPlaces = emptyList(),
        stays = listOf(Stay(id = "s1", placeId = "p", cancellationDeadline = "2026-11-10", confirmationNumber = "CONF-1")),
        flights = emptyList(), trains = emptyList(), days = emptyList(), dayItems = emptyList(), notes = emptyList(), conflicts = emptyList(),
    )

    private fun run(enabled: Boolean, today: String = "2026-11-08"): Result {
        val worker = TestListenableWorkerBuilder<DeadlineWorker>(ctx).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                DeadlineWorker(appContext, workerParameters) { ReminderInput(enabled, snapshot, LocalDate.parse(today)) }
        }).build()
        return runBlocking { worker.doWork() }
    }

    @Test fun disabledPostsNothing() {
        shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Result.success(), run(enabled = false))
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test fun missingPermissionPostsNothing() {
        shadowOf(ctx as android.app.Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Result.success(), run(enabled = true))
        assertTrue(nm.allNotifications.isEmpty())
    }

    @Test fun postsAndRemembers() {
        shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Result.success(), run(enabled = true))
        val n = nm.allNotifications.single()
        assertEquals("Stornofrist: Hotel Sakura", n.extras.getString("android.title"))
        assertTrue(ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).getStringSet("sent", null)!!.contains("s1|2026-11-10|3"))
        // second run: nothing new
        run(enabled = true)
        assertEquals(1, nm.allNotifications.size)
        // next offset is due the day before
        run(enabled = true, today = "2026-11-09")
        assertEquals(2, nm.allNotifications.size)
    }
}
