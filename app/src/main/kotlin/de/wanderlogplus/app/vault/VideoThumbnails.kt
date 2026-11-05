package de.wanderlogplus.app.vault

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** On-device JPEG thumbnail of a video; null when the file cannot be decoded. */
fun interface VideoThumbnails {
    fun thumbnail(uri: Uri): ByteArray?
}

/**
 * First frame via [MediaMetadataRetriever], scaled to [THUMB_SIDE] px on the longest side, JPEG quality 80.
 * Failures (unsupported codec, vanished file) are null – the drain then drops the row like a vanished photo.
 */
class MediaStoreVideoThumbnails(private val appContext: Context) : VideoThumbnails {
    override fun thumbnail(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val frame = retriever.frameAtTime ?: return null
            try {
                frameToJpeg(frame, THUMB_SIDE, JPEG_QUALITY)
            } finally {
                frame.recycle()
            }
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val THUMB_SIDE = 1280
        const val JPEG_QUALITY = 80
    }
}

/** Scale so the longest side is [maxSide] (never upscale) and encode JPEG. Test-visible for the scaling contract. */
internal fun frameToJpeg(frame: Bitmap, maxSide: Int, quality: Int): ByteArray {
    val longest = maxOf(frame.width, frame.height).coerceAtLeast(1)
    val scaled = if (longest <= maxSide) {
        frame
    } else {
        val ratio = maxSide.toFloat() / longest
        Bitmap.createScaledBitmap(
            frame,
            (frame.width * ratio).toInt().coerceAtLeast(1),
            (frame.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== frame) scaled.recycle()
    return out.toByteArray()
}
