package de.wanderlogplus.app.ui.share

import de.wanderlogplus.app.files.AttachmentTooLargeException
import de.wanderlogplus.app.ui.attachments.AttachmentSource
import kotlinx.coroutines.CancellationException
import java.io.InputStream

/** What [importShared] needs from the app: stores the stream, then attaches it to [target]. */
fun interface ShareSink {
    /** Throws [AttachmentTooLargeException] before anything is created when the file is too large. */
    suspend fun add(target: ShareTarget, input: InputStream, fileName: String, mimeType: String)
}

data class ShareResult(val attached: Int, val skippedTooLarge: Int, val failed: Int) {
    /** Fixed German text, never file names. */
    fun message(): String = listOfNotNull(
        when {
            attached == 1 -> "Angehängt"
            attached > 1 -> "$attached Dateien angehängt"
            else -> null
        },
        skippedTooLarge.takeIf { it > 0 }?.let { "$it Datei(en) zu groß (max. 25 MB)" },
        failed.takeIf { it > 0 }?.let { "$it Datei(en) konnten nicht angehängt werden" },
    ).joinToString("\n").ifEmpty { "Nichts angehängt" }
}

/** Copies every shared file (call before the activity finishes: the URI grants die with it). */
suspend fun importShared(sink: ShareSink, sources: List<AttachmentSource>, target: ShareTarget): ShareResult {
    var attached = 0
    var tooLarge = 0
    var failed = 0
    for (source in sources) {
        try {
            val input = source.open()
            if (input == null) {
                failed++
                continue
            }
            input.use { sink.add(target, it, source.fileName(), source.mimeType()) }
            attached++
        } catch (e: CancellationException) {
            throw e
        } catch (e: AttachmentTooLargeException) {
            tooLarge++
        } catch (e: Exception) {
            failed++ // no details: they could carry file names
        }
    }
    return ShareResult(attached, tooLarge, failed)
}
