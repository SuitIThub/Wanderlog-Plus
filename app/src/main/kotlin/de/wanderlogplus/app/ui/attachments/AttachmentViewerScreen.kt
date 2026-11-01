package de.wanderlogplus.app.ui.attachments

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.files.AttachmentFiles
import de.wanderlogplus.shared.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

const val ATTACHMENT_ROUTE = "attachment/{attachmentId}"
fun attachmentRoute(id: String) = "attachment/$id"

@Composable
fun AttachmentViewerScreen(
    attachment: Attachment?,
    files: AttachmentFiles,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text(attachment?.fileName.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        val target = attachment?.let { openTargetFor(it, files) }
        when (target) {
            is OpenTarget.Image -> AsyncImage(
                model = target.file,
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag("viewer-image"),
            )
            is OpenTarget.Pdf -> PdfPages(target.file, Modifier.fillMaxSize().testTag("viewer-pdf"))
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Datei nicht verfügbar", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private sealed interface PdfState {
    object Loading : PdfState
    object Failed : PdfState
    class Open(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) : PdfState
}

private fun openPdf(file: File): PdfState.Open? = try {
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    try {
        PdfState.Open(fd, PdfRenderer(fd))
    } catch (e: Exception) {
        fd.close()
        null
    }
} catch (e: Exception) {
    null
}

/** Renders each page as a bitmap of screen width once it becomes visible. The PDF is opened off the main thread. */
@Composable
private fun PdfPages(file: File, modifier: Modifier = Modifier) {
    val state by produceState<PdfState>(PdfState.Loading, file) {
        // Not cancellable, so an opened file is never lost; awaitDispose closes it even when we were cancelled meanwhile.
        val open = withContext(Dispatchers.IO + NonCancellable) { openPdf(file) }
        if (open == null) {
            value = PdfState.Failed
            return@produceState
        }
        value = open
        awaitDispose {
            // Closing synchronizes on the renderer, so it waits for a running render; later renders see the cancelled scope and skip.
            synchronized(open.renderer) {
                try { open.renderer.close() } catch (_: Exception) {}
                try { open.fd.close() } catch (_: Exception) {}
            }
        }
    }
    val opened = when (val st = state) {
        PdfState.Loading -> {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.testTag("viewer-pdf-loading")) }
            return
        }
        PdfState.Failed -> {
            Box(modifier, contentAlignment = Alignment.Center) { Text("PDF kann nicht angezeigt werden") }
            return
        }
        is PdfState.Open -> st
    }
    val renderer = opened.renderer
    val pageCount = remember(renderer) { renderer.pageCount }
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(Modifier.fillMaxSize()) {
            items((0 until pageCount).toList(), key = { it }) { index ->
                val bitmap by produceState<Bitmap?>(null, renderer, index, widthPx) {
                    value = withContext(Dispatchers.Default) {
                        val ctx = currentCoroutineContext()
                        renderPage(renderer, index, widthPx) { ctx.isActive }
                    }
                }
                val b = bitmap
                if (b != null) {
                    Image(
                        b.asImageBitmap(), contentDescription = "Seite ${index + 1}",
                        modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f / 1.414f))
                }
            }
        }
    }
}

private fun renderPage(renderer: PdfRenderer, index: Int, widthPx: Int, active: () -> Boolean): Bitmap? = synchronized(renderer) {
    if (!active()) return@synchronized null
    try {
        val page = renderer.openPage(index)
        try {
            // Very tall pages are scaled down so the bitmap stays at most 4x as high as wide.
            var w = widthPx.coerceAtLeast(1)
            var h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
            if (h > 4 * w) {
                h = 4 * w
                w = (h.toFloat() * page.width / page.height).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
