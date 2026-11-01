package de.wanderlogplus.app.ui.attachments

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentViewerTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun pdfShowsProgressWhileOpeningOffMainThread() {
        val files = AttachmentFiles(tmp.newFolder("att"))
        val imported = files.importStream("%PDF-1.4".byteInputStream())
        val a = Attachment("a1", EntityType.TRAIN, "tr1", imported.hash, "Ticket.pdf", "application/pdf", imported.size)
        rule.mainClock.autoAdvance = false
        rule.setContent { WanderlogPlusTheme { AttachmentViewerScreen(a, files, onBack = {}) } }
        // The result of the open on Dispatchers.IO only arrives with the next frame.
        rule.onNodeWithTag("viewer-pdf-loading", useUnmergedTree = true).assertExists()
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("viewer-pdf-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("PDF kann nicht angezeigt werden").assertDoesNotExist()
    }
}
