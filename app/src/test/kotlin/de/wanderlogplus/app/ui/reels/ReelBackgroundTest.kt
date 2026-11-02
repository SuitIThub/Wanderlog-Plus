package de.wanderlogplus.app.ui.reels

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.EXTRA_REEL_JOB
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.places.PlacesScreen
import de.wanderlogplus.app.ui.share.ReelPopup
import de.wanderlogplus.app.ui.share.ReelShareState
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.ReelCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h900dp")
class ReelBackgroundTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val done = AiJobInfo(
        "job-1", "reel", 0, "DONE",
        candidates = listOf(ReelCandidate("A", onRoute = true), ReelCandidate("B", onRoute = false), ReelCandidate("C", onRoute = true)),
    )

    private fun worker(lookup: JobLookup): ReelWatchWorker =
        TestListenableWorkerBuilder<ReelWatchWorker>(context)
            .setInputData(workDataOf(ReelWatchWorker.KEY_JOB to "job-1"))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    ReelWatchWorker(appContext, workerParameters, lookup, pollMillis = 1, attempts = 3)
            })
            .build()

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun notifiesWhenTheJobIsDone() = runBlocking {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var calls = 0
        val result = worker { calls++; if (calls < 2) done.copy(status = "RESEARCHING") else done }.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val n = notifications().single()
        assertEquals("3 Orte im Reel gefunden", n.extras.getString("android.title"))
        assertEquals("2 davon auf eurer Route. Tippen zum Ansehen.", n.extras.getString("android.text"))
        // A tap opens the result in the app.
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals("job-1", intent.getStringExtra(EXTRA_REEL_JOB))
    }

    @Test fun stillRunningIsRetriedLater() = runBlocking {
        val result = worker { done.copy(status = "ANALYZING") }.doWork()
        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(notifications().isEmpty())
    }

    @Test fun failureTextsAndCounts() {
        assertEquals("Reel-Auswertung fehlgeschlagen", reelNotificationText(done.copy(status = "FAILED", error = "download_failed")).first)
        assertEquals("Im Reel wurde kein konkreter Ort erkannt.", reelNotificationText(done.copy(candidates = emptyList())).second)
        assertEquals("1 Ort im Reel gefunden", reelNotificationText(done.copy(candidates = listOf(ReelCandidate("A", onRoute = true)))).first)
    }

    @Test fun popupTellsAboutTheBackgroundWork() {
        var closed = false
        val state = androidx.compose.runtime.mutableStateOf<ReelShareState>(ReelShareState.Working)
        rule.setContent { WanderlogPlusTheme { ReelPopup(state.value, onClose = { closed = true }) } }
        rule.onNodeWithText("Reel wird an den Server geschickt …").assertExists()
        state.value = ReelShareState.Started("job-1")
        rule.onNodeWithText("🎬 Reel wird ausgewertet").assertExists()
        rule.onNodeWithTag("reel-popup-ok").performClick()
        assertTrue(closed)
        state.value = ReelShareState.Failed("Server nicht erreichbar")
        rule.onNodeWithText("Server nicht erreichbar").assertExists()
    }

    @Test fun placesOverviewOpensReels() {
        var opened = false
        rule.setContent { WanderlogPlusTheme { PlacesScreen(Fixtures.trip(), onOpenRegion = {}, onReels = { opened = true }) } }
        rule.onNodeWithTag("places-reels").performClick()
        assertTrue(opened)
    }

    @Test fun noteListsSources() {
        val note = reelNote(ReelCandidate("A", sources = listOf("https://example.jp/a")), null)
        assertTrue(note.contains("**Quellen**\n- https://example.jp/a"))
    }
}
