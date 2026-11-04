package de.wanderlogplus.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.query.OpenReservation
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
class ReservationRemindersTest {
    private val places = mapOf("p" to Place(id = "p", name = "Moosgarten"))
    private val open = listOf(OpenReservation(SavedPlace("s1", "p"), "2027-05-20", "Postkarte Wochen vorher"))

    private fun due(today: String, sent: Set<String> = emptySet()) = dueReservationReminders(open, places, LocalDate.parse(today), sent)

    @Test fun remindsThirtyFourteenSevenAndTwoDaysBefore() {
        assertTrue(due("2027-04-19").isEmpty())
        val first = due("2027-04-20").single()
        assertEquals("res|s1|2027-05-20|30", first.key)
        assertEquals("Reservierung: Moosgarten", first.title)
        assertEquals("Eingeplant am Do, 20.05. (noch 30 Tage) – schon reserviert?", first.text)
        assertEquals("s1", first.savedPlaceId)
        assertTrue(first.detail!!.endsWith("Postkarte Wochen vorher"))
        assertEquals("res|s1|2027-05-20|14", due("2027-05-10").single().key) // smallest due step only
        assertTrue(due("2027-05-10", setOf("res|s1|2027-05-20|14")).isEmpty())
        assertEquals("Morgen eingeplant – schon reserviert?", due("2027-05-19").single().text)
        assertTrue(due("2027-05-21").isEmpty())
    }

    @Test fun workerPostsTheReminderWithAReservedButton() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(ctx as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val snapshot = TripSnapshot(
            trip = null, regions = emptyList(), places = places,
            savedPlaces = listOf(SavedPlace("s1", "p", dossier = PlaceDossier(hints = listOf("Reservierung Wochen vorher nötig")))),
            stays = emptyList(), flights = emptyList(), trains = emptyList(),
            days = listOf(Day("d", "2027-05-20")), dayItems = listOf(DayItem("i", "d", "a", placeId = "p")),
            notes = emptyList(), conflicts = emptyList(),
        )
        val worker = TestListenableWorkerBuilder<DeadlineWorker>(ctx).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                DeadlineWorker(appContext, workerParameters) { ReminderInput(true, snapshot, LocalDate.parse("2027-05-13")) }
        }).build()
        runBlocking { worker.doWork() }
        val n = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals("Reservierung: Moosgarten", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("Reserviert", n.actions.single().title.toString())
    }
}
