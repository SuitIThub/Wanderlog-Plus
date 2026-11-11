package de.wanderlogplus.app.ui.reels

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.places.PlaceDetailScreen
import de.wanderlogplus.app.ui.places.ResearchAction
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.JobStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class JobProgressTest {
    @get:Rule val rule = createComposeRule()

    private val t0 = 1_800_000_000_000L

    private val running = AiJobInfo(
        id = "j1", kind = "research", createdAt = t0, status = "RESEARCHING", progress = 1, total = 3,
        items = listOf(
            JobItem("a", "Fushimi Inari, Kyoto", "DONE", "Muss man sehen", t0 + 1_000, t0 + 61_000),
            JobItem("b", "Kinkaku-ji, Kyoto", "RUNNING", "Websuche: 5 Quellen (japan-guide.com)", t0 + 2_000),
            JobItem("c", "Nishiki Markt, Kyoto", "WAITING"),
        ),
        steps = listOf(
            JobStep(t0, "Recherche für 3 Orte nach 8 Kriterien"),
            JobStep(t0 + 61_000, "Fushimi Inari: fertig, Muss man sehen", "OK"),
            JobStep(t0 + 62_000, "Kinkaku-ji: Websuche: 5 Quellen (japan-guide.com)"),
        ),
    )

    @Test fun showsStatusTimeItemsAndNewestLogFirst() {
        rule.setContent { WanderlogPlusTheme { JobProgressScreen(running, onBack = {}, clock = { t0 + 83_000 }) } }
        rule.onNodeWithText("Ablauf · Recherche").assertExists()
        rule.onNodeWithText("läuft seit 1:23").assertExists()
        rule.onNodeWithText("1 von 3 Orten fertig · 1 in Arbeit").assertExists()
        rule.onNodeWithTag("job-item-a").assertExists()
        rule.onNodeWithText("1:00").assertExists() // duration of the finished place
        rule.onNodeWithText("Websuche: 5 Quellen (japan-guide.com)").assertExists()
        rule.onNodeWithText("wartet").assertExists()
        rule.onNodeWithText("Kinkaku-ji: Websuche: 5 Quellen (japan-guide.com)").assertExists()
        rule.onNodeWithTag("job-result").assertDoesNotExist()
    }

    @Test fun finishedReelOffersResultAndFailedShowsReason() {
        var opened = 0
        val done = running.copy(kind = "reel", status = "DONE", finishedAt = t0 + 161_000, items = emptyList())
        rule.setContent { WanderlogPlusTheme { JobProgressScreen(done, onBack = {}, onOpenResult = { opened++ }) } }
        rule.onNodeWithText("Dauer 2:41").assertExists()
        rule.onNodeWithTag("job-result").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun failedJobExplainsWhy() {
        val failed = running.copy(status = "FAILED", error = "download_failed", finishedAt = t0 + 5_000)
        rule.setContent { WanderlogPlusTheme { JobProgressScreen(failed, onBack = {}) } }
        rule.onNodeWithText(errorText("download_failed")).assertExists()
    }

    @Test fun jobListOpensAJob() {
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { AiJobsScreen(listOf(running), onOpen = { opened = it }, onBack = {}, clock = { t0 + 10_000 }) } }
        rule.onNodeWithText("Recherche · 3 Orte").assertExists()
        rule.onNodeWithTag("ai-job-j1").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("j1", opened)
    }

    @Test fun jobListStartsTheGermanTranslation() {
        var started = 0
        val translate = running.copy(id = "t1", kind = "translate", total = 0)
        rule.setContent { WanderlogPlusTheme { AiJobsScreen(listOf(translate), onOpen = {}, onBack = {}, onTranslate = { started++ }, clock = { t0 + 10_000 }) } }
        rule.onNodeWithText("Übersetzung").assertExists()
        rule.onNodeWithTag("translate-texts").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, started)
    }

    @Test fun reelResultAndPlaceResearchLeadToTheAblauf() {
        var shown = 0
        val reel = running.copy(kind = "reel", status = "ANALYZING", steps = listOf(JobStep(t0, "Bilder und Ton werden herausgelöst (ffmpeg) …")))
        rule.setContent {
            WanderlogPlusTheme {
                ReelJobScreen(reel, null, imported = false, onImport = { _, _ -> }, onOpenPlaces = {}, onBack = {}, onShowProgress = { shown++ })
            }
        }
        rule.onNodeWithTag("reel-last-step").assertExists()
        rule.onNodeWithTag("reel-progress-live").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, shown)
    }

    @Test fun placeResearchShowsTheAblaufLink() {
        var shown = 0
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    Fixtures.trip(), "s-nudel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    research = ResearchAction(true, {}, { shown++ }),
                )
            }
        }
        rule.onNodeWithTag("tab-research").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithText("Ablauf live ansehen").assertExists()
        rule.onNodeWithTag("place-research-progress").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, shown)
    }

    @Test fun helpers() {
        assertEquals("0:42", durationText(42_000))
        assertEquals("1:02:03", durationText(3_723_000))
        assertNull(itemsSummary(emptyList()))
        assertEquals("Einschätzung", jobKindLabel("assess"))
    }
}
