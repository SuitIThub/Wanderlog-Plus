package de.wanderlogplus.app.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Longest side of a gallery photo after upload; enough for a phone or a tablet, a fraction of a camera original. */
const val PHOTO_MAX_SIDE = 2560

/**
 * A camera photo as JPEG of at most [maxSide] pixels on its long side, turned upright by its EXIF orientation
 * (the metadata is not kept: no location in the shared file). Null when [bytes] is no decodable image; then the
 * original is used.
 */
fun shrinkPhoto(bytes: ByteArray, maxSide: Int = PHOTO_MAX_SIDE, quality: Int = 85): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rotation = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (e: Exception) {
        0f
    }
    val longSide = maxOf(decoded.width, decoded.height)
    val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
    val matrix = Matrix().apply { if (scale != 1f) postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
    val upright = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    return ByteArrayOutputStream().use { out ->
        upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}
