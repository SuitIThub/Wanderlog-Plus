package de.wanderlogplus.app.ui.attachments

import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.shared.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenRoutingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun files() = AttachmentFiles(tmp.newFolder("att"))

    private fun stored(files: AttachmentFiles, bytes: String, name: String, mime: String): Attachment {
        val imp = files.importStream(bytes.byteInputStream())
        return Attachment("a-$name", fileHash = imp.hash, fileName = name, mimeType = mime, size = imp.size)
    }

    @Test fun jpegOpensAsImage() {
        val f = files()
        val a = stored(f, "jpg", "x.jpg", "image/jpeg")
        assertEquals(OpenTarget.Image(f.fileFor(a.fileHash)!!), openTargetFor(a, f))
    }

    @Test fun pdfOpensAsPdf() {
        val f = files()
        val a = stored(f, "pdf", "x.pdf", "application/pdf")
        assertEquals(OpenTarget.Pdf(f.fileFor(a.fileHash)!!), openTargetFor(a, f))
    }

    @Test fun zipOpensExternally() {
        val f = files()
        val a = stored(f, "zip", "x.zip", "application/zip")
        assertEquals(OpenTarget.External(f.fileFor(a.fileHash)!!, "application/zip"), openTargetFor(a, f))
    }

    @Test fun missingFileIsUnavailable() {
        val f = files()
        val a = Attachment("a1", fileHash = "0".repeat(64), fileName = "x.pdf", mimeType = "application/pdf")
        assertTrue(openTargetFor(a, f) === OpenTarget.Unavailable)
    }
}
