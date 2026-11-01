package de.wanderlogplus.app.ui.share

import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.app.ui.attachments.AttachmentSource
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream

class ShareImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Src(val name: String, val size: Long, val openable: Boolean = true) : AttachmentSource {
        override fun fileName() = name
        override fun mimeType() = "application/pdf"
        override fun open(): InputStream? = if (!openable) null else object : InputStream() {
            var left = size
            override fun read(): Int = if (left-- > 0) 7 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                java.util.Arrays.fill(b, off, off + n, 7.toByte())
                left -= n
                return n
            }
        }
    }

    /** Imports like AppContainer.addAttachment: file first (size check), then the target is resolved and the record created. */
    private inner class FakeSink : ShareSink {
        val files = AttachmentFiles(tmp.newFolder("att"))
        val created = mutableListOf<String>()
        var resolved = 0
        override suspend fun add(target: ShareTarget, input: InputStream, fileName: String, mimeType: String) {
            files.importStream(input)
            resolved++
            created += fileName
        }
    }

    private val target = ShareTarget(EntityType.FLIGHT, "f1", "Flug", null, ShareGroup.FLIGHT)

    @Test fun tooLargeShareCreatesNoAttachment() = runBlocking {
        val sink = FakeSink()
        val r = importShared(sink, listOf(Src("big.pdf", 25L * 1024 * 1024 + 1)), target)
        assertEquals(0, sink.created.size)
        assertEquals(0, sink.resolved)
        assertEquals(1, r.skippedTooLarge)
        assertEquals(0, r.attached)
        assertEquals("1 Datei(en) zu groß (max. 25 MB)", r.message())
    }

    @Test fun mixedShareAttachesSmallOnes() = runBlocking {
        val sink = FakeSink()
        val r = importShared(sink, listOf(Src("a.pdf", 10), Src("big.pdf", 25L * 1024 * 1024 + 1), Src("b.pdf", 20), Src("bad", 0, openable = false)), target)
        assertEquals(listOf("a.pdf", "b.pdf"), sink.created)
        assertEquals(2, r.attached)
        assertEquals(1, r.skippedTooLarge)
        assertEquals(1, r.failed)
        assertEquals("2 Dateien angehängt", r.message().lines().first())
    }

    @Test fun singleSuccessMessage() = runBlocking {
        val r = importShared(FakeSink(), listOf(Src("a.pdf", 10)), target)
        assertEquals("Angehängt", r.message())
    }
}
