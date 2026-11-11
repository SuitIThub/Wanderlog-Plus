package de.wanderlogplus.app.ui.guides

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.chat.ChatCardView
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.sync.AiJobInfo
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.JobItem
import de.wanderlogplus.shared.sync.JobStep
import de.wanderlogplus.shared.sync.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class GuideJobsTest {
    @get:Rule val rule = createComposeRule()

    private val hour = 3_600_000L
    private val now = 100 * hour
    private val onsen = Guide("g1", topic = "onsen", title = "Onsen & Sentō", summary = "Kurz", writtenAt = now - 5 * hour)

    private fun job(id: String, status: String, createdAt: Long, guideIds: List<String> = listOf("g1"), items: List<JobItem> = emptyList(), step: String? = null) =
        AiJobInfo(id, "guides", createdAt, status, total = 1, guideIds = guideIds, items = items, steps = listOfNotNull(step?.let { JobStep(createdAt, it) }))

    private fun snapshot(vararg guides: Guide) = TripSnapshot.from(
        guides.map { g -> EntityRecord(EntityType.GUIDE, g.id, EntityCodec.fields(g).mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) }) },
    )

    @Test
    fun runningJobMarksItsGuide() {
        val state = GuideJobs.stateOf(onsen, listOf(job("j1", "RESEARCHING", now - hour, step = "Recherche läuft")), now)
        assertTrue(state is GuideJobState.Running)
        assertNull(GuideJobs.stateOf(onsen.copy(id = "other"), listOf(job("j1", "RESEARCHING", now - hour)), now))
    }

    @Test
    fun failedRewriteShowsUntilTheGuideIsWrittenAgain() {
        val failed = job("j1", "DONE", now - hour, items = listOf(JobItem("guide-0", "Onsen", state = "FAILED", detail = "KI nicht erreichbar")))
        val state = GuideJobs.stateOf(onsen, listOf(failed), now)
        assertTrue(state is GuideJobState.Failed)
        assertEquals("KI nicht erreichbar", (state as GuideJobState.Failed).detail)
        // Written after the failed job: the failure is over.
        assertNull(GuideJobs.stateOf(onsen.copy(writtenAt = now), listOf(failed), now))
        // A day later the note is gone too.
        assertNull(GuideJobs.stateOf(onsen, listOf(failed), now + 30 * hour))
        // A job that went through says nothing.
        assertNull(GuideJobs.stateOf(onsen, listOf(job("j2", "DONE", now - hour)), now))
    }

    @Test
    fun guidePageShowsTheJobAndWhenItWasWritten() {
        rule.setContent {
            WanderlogPlusTheme {
                GuideScreen(
                    snapshot(onsen), "g1", null, onBack = {}, onOpenGuide = {}, onImprove = null,
                    jobs = listOf(job("j1", "RESEARCHING", now - hour, step = "Recherche läuft")),
                )
            }
        }
        rule.onNodeWithTag("guide-job-running").assertIsDisplayed()
        rule.onNodeWithText("Recherche läuft", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("guide-written-at").assertExists()
    }

    @Test
    fun overviewShowsJobsStartedElsewhere() {
        rule.setContent {
            WanderlogPlusTheme {
                GuidesScreen(snapshot(onsen), null, listOf(job("j1", "RESEARCHING", now - hour, items = listOf(JobItem("guide-0", "Onsen & Sentō", state = "RUNNING")))), onOpen = {}, onBack = {})
            }
        }
        rule.onNodeWithTag("guides-running").assertIsDisplayed()
        rule.onNodeWithText("Wird überarbeitet: Onsen & Sentō").assertIsDisplayed()
    }

    @Test
    fun chatCardFollowsTheJobAndOpensTheGuide() {
        var opened: String? = null
        val card = ChatCard(ChatCard.KIND_GUIDE, "Onsen & Sentō", subtitle = "Wird überarbeitet", guideId = "g1", jobId = "j1")
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(
                    LocalGuideJobs provides listOf(job("j1", "DONE", now - hour)),
                    LocalOpenGuide provides { id: String -> opened = id },
                ) {
                    ChatCardView(card, null, onOpenPlace = {}, onOpenUrl = {})
                }
            }
        }
        rule.onNodeWithText("Fertig", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("chat-guide-card").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("g1", opened)
    }

    @Test
    fun chatCardForANewGuideOpensItOnceWritten() {
        var opened: String? = null
        val card = ChatCard(ChatCard.KIND_GUIDE, "Kiso-Wanderwege", subtitle = "Wird neu geschrieben", jobId = "j2")
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(
                    LocalGuideJobs provides listOf(job("j2", "DONE", now - hour, guideIds = listOf("g9"))),
                    LocalOpenGuide provides { id: String -> opened = id },
                ) {
                    ChatCardView(card, null, onOpenPlace = {}, onOpenUrl = {})
                }
            }
        }
        rule.onNodeWithTag("chat-guide-card").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("g9", opened)
    }

    @Test
    fun aFailedLinkOnlyMarksItsOwnGuide() {
        val j = job("l1", "DONE", 100, guideIds = listOf("g1", "g2"), items = listOf(JobItem("link-g1", "A", "FAILED", "KI nicht erreichbar"), JobItem("link-g2", "B", "DONE", "keine Links")))
        org.junit.Assert.assertNull(GuideJobs.stateOf(Guide("g2", title = "B"), listOf(j), now = 200))
        org.junit.Assert.assertTrue(GuideJobs.stateOf(Guide("g1", title = "A"), listOf(j), now = 200) is GuideJobState.Failed)
    }
}
