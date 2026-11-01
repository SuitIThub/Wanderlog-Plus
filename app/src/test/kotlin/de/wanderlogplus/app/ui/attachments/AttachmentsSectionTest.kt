package de.wanderlogplus.app.ui.attachments

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class AttachmentsSectionTest {
    @get:Rule val rule = createComposeRule()

    private val ticket = Attachment("a1", fileName = "Ticket.pdf", mimeType = "application/pdf", size = 1_234_567)
    private var deleted = mutableListOf<String>()
    private var opened = mutableListOf<String>()
    private var added = 0

    private fun show(available: (String) -> Boolean) {
        rule.setContent {
            WanderlogPlusTheme {
                AttachmentsSection(
                    listOf(ticket), available,
                    onAdd = { added++ }, onOpen = { opened += it.id }, onDelete = { deleted += it.id },
                )
            }
        }
    }

    @Test fun showsNameSizeAndAddButton() {
        show { true }
        rule.onNodeWithText("Anhänge").assertIsDisplayed()
        rule.onNodeWithText("Ticket.pdf").assertIsDisplayed()
        rule.onNodeWithText("1,2 MB", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("attachment-add").performClick()
        assertEquals(1, added)
        rule.onNodeWithTag("attachment-a1").performClick()
        assertEquals(listOf("a1"), opened)
    }

    @Test fun showsUnavailableHint() {
        show { false }
        rule.onNodeWithText("nicht offline verfügbar", substring = true).assertIsDisplayed()
    }

    @Test fun deleteAsksForConfirmation() {
        show { true }
        rule.onNodeWithTag("attachment-menu-a1").performClick()
        rule.onNodeWithText("Löschen").performClick()
        rule.onNodeWithText("Anhang löschen?").assertIsDisplayed()
        assertEquals(emptyList<String>(), deleted)
        rule.onNodeWithTag("attachment-delete-confirm").performClick()
        assertEquals(listOf("a1"), deleted)
    }

    @Test fun deleteCanBeCancelled() {
        show { true }
        rule.onNodeWithTag("attachment-menu-a1").performClick()
        rule.onNodeWithText("Löschen").performClick()
        rule.onNodeWithText("Abbrechen").performClick()
        assertEquals(emptyList<String>(), deleted)
    }

    @Test fun formatBytesGerman() {
        assertEquals("1,2 MB", formatBytes(1_234_567))
        assertEquals("332 KB", formatBytes(340_000))
        assertEquals("12 B", formatBytes(12))
        assertEquals("1,0 MB", formatBytes(1_048_064))
        assertEquals("1,0 MB", formatBytes(1_048_575))
        assertEquals("1023 KB", formatBytes(1_047_551))
    }
}
