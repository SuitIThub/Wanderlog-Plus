package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class LogisticsDetailTest {
    @get:Rule val rule = createComposeRule()

    @Test fun detailShowsConfirmationAndTravelers() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snap, EntityType.FLIGHT, "f1", onBack = {}) } }
        rule.onNodeWithText("Flug LH 716").assertIsDisplayed()
        rule.onNodeWithText("ABC123").assertIsDisplayed()
        rule.onNodeWithText("Max Muster").assertIsDisplayed()
        rule.onNodeWithText("Frankfurt (FRA)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Live-Status: –").assertIsDisplayed()
        rule.onNodeWithText("Sitz", substring = true).assertIsDisplayed() // note as Markdown
        rule.onNodeWithTag("route-fallback").assertExists() // route map below the hero
    }

    @Test fun stayDetailShowsBookingAndCancellation() {
        val snap = LogisticsFixture.snapshot()
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snap, EntityType.STAY, "s1", onBack = {}) } }
        rule.onNodeWithText("Hotel Kyoto").assertIsDisplayed()
        rule.onNodeWithText("STAY42").assertIsDisplayed()
        rule.onNodeWithText("Max Muster").assertIsDisplayed()
        rule.onNodeWithText("Sa, 01.05.").assertIsDisplayed()
        rule.onNodeWithText("Kostenlos bis 1. Mai").assertIsDisplayed()
    }

    @Test fun detailShowsAttachmentsAndLinks() {
        val snap = LogisticsFixture.snapshot(withAttachment = true)
        val added = mutableListOf<Pair<EntityType, String>>()
        val openedAttachments = mutableListOf<Attachment>()
        val links = mutableListOf<ExternalLink>()
        val actions = AttachmentActions(
            available = { true },
            onAdd = { t, id -> added += t to id },
            onAddToDay = {},
            onOpen = { openedAttachments += it },
            onDelete = {},
        )
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsDetailScreen(snap, EntityType.STAY, "s1", onBack = {}, attachments = actions, onOpenLink = { links += it })
            }
        }
        rule.onNodeWithTag("attachment-a1").assertIsDisplayed().performClick()
        rule.onNodeWithTag("attachment-add").performClick()
        rule.onNodeWithTag("link-NAVIGATE").performClick()
        rule.onNodeWithTag("link-TRANSIT").assertExists()
        assertEquals(listOf("a1"), openedAttachments.map { it.id })
        assertEquals(listOf(EntityType.STAY to "s1"), added)
        assertEquals(listOf(LinkKind.NAVIGATE), links.map { it.kind })
    }

    @Test fun moveDetailHasNoNavigationButAttachments() {
        val snap = LogisticsFixture.snapshot(withAttachment = true)
        val actions = AttachmentActions({ true }, { _, _ -> }, {}, {}, {})
        rule.setContent {
            WanderlogPlusTheme { LogisticsDetailScreen(snap, EntityType.FLIGHT, "f1", onBack = {}, attachments = actions, onOpenLink = {}) }
        }
        rule.onNodeWithTag("attachment-a2").assertExists()
        rule.onNodeWithTag("link-NAVIGATE").assertDoesNotExist()
    }

    @Test fun missingEntryShowsHint() {
        val snap = LogisticsFixture.snapshot()
        var back = 0
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snap, EntityType.TRAIN, "gone", onBack = { back++ }) } }
        rule.onNodeWithText("Diese Buchung existiert nicht mehr.").assertIsDisplayed()
        rule.onNodeWithTag("hero-back").performClick()
        assertEquals(1, back)
    }

    @Test fun unknownTypeShowsNotFoundWithBack() {
        val snap = LogisticsFixture.snapshot()
        var back = 0
        val type = logisticsTypeOf("boat")
        assertEquals(null, type)
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(snap, type, "f1", onBack = { back++ }) } }
        rule.onNodeWithText("Buchung nicht gefunden").assertIsDisplayed()
        rule.onNodeWithText("Diese Buchung existiert nicht mehr.").assertIsDisplayed()
        rule.onNodeWithTag("hero-back").performClick()
        assertEquals(1, back)
    }
}
