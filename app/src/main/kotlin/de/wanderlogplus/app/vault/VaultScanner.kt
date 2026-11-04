package de.wanderlogplus.app.vault

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import de.wanderlogplus.app.data.VaultDao
import de.wanderlogplus.app.data.VaultFoundEntity
import de.wanderlogplus.app.data.VaultScanStateEntity
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.VaultScanStates
import de.wanderlogplus.shared.sync.VaultScanUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Walks the device's images, scores each with [classifier] (on-device only) and stores the flagged ones.
 * Every server call is best effort: an unreachable server never fails the scan, it only skips the report
 * or leaves uploads queued for the next run. Cancellation is the only exception that escapes [run].
 */
class VaultScanner(
    private val appContext: Context,
    private val dao: VaultDao,
    private val classifier: SensitiveClassifier = SkinRatioClassifier(),
    private val api: suspend () -> SyncApi?,
    private val device: String = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
    /** Reads a media item; injectable so tests can skip the content resolver. */
    private val openStream: (Uri) -> java.io.InputStream? = { appContext.contentResolver.openInputStream(it) },
) {
    data class Result(val total: Int, val scanned: Int, val found: Int, val uploaded: Int)

    suspend fun run(): Result = withContext(Dispatchers.IO) {
        // Automatic scans must no-op silently without the media permission (the user never granted it on purpose).
        if (!hasMediaPermission()) return@withContext Result(0, 0, 0, 0)
        var total = 0
        var scanned = 0
        var found = 0
        var uploaded = 0
        var galleryTotal = 0
        var scored = 0
        var decodeFailed = 0
        var maxScore = 0.0

        suspend fun publish(state: String) {
            publish(state, total, scanned, found, galleryTotal, scored, decodeFailed, maxScore)
        }

        try {
            val known = dao.foundUris().toHashSet()
            galleryTotal = countAllImages()
            val items = queryImages()
            total = items.size
            publish(VaultScanStates.RUNNING)
            for (item in items) {
                if (item.uri.toString() in known) {
                    scanned++
                    continue
                }
                val score = scoreOf(item.uri)
                if (score == null) {
                    decodeFailed++
                } else {
                    scored++
                    if (score > maxScore) maxScore = score
                    if (classifier.flagged(score)) {
                        dao.insertFound(
                            VaultFoundEntity(
                                uri = item.uri.toString(),
                                fileName = item.fileName,
                                mimeType = item.mimeType,
                                score = score,
                                foundAt = System.currentTimeMillis(),
                                uploadedAt = null,
                            )
                        )
                        found++
                    }
                }
                scanned++
                if (scanned % PROGRESS_EVERY == 0) publish(VaultScanStates.RUNNING)
            }
            publish(VaultScanStates.DONE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // State only, no data: messages can contain paths. The scan still ends "cleanly" for WorkManager.
            publish(VaultScanStates.FAILED)
            return@withContext Result(total, scanned, found, 0)
        }
        uploaded = drainUploads().uploaded
        Result(total, scanned, found, uploaded)
    }

    // -- MediaStore --

    /** Full access, or the Android-14 partial pick ("selected photos"): both let [queryImages] read something. */
    private fun hasMediaPermission(): Boolean {
        val full = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (androidx.core.content.ContextCompat.checkSelfPermission(appContext, full) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 &&
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private data class MediaItem(val uri: Uri, val fileName: String, val mimeType: String)

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun queryImages(): List<MediaItem> {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val out = ArrayList<MediaItem>()
        appContext.contentResolver.query(collection, projection, null, null, MediaStore.Images.Media._ID)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val mime = c.getString(mimeCol).orEmpty()
                if (mime !in SUPPORTED_MIME) continue
                out += MediaItem(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    fileName = c.getString(nameCol)?.take(200)?.ifBlank { "image" } ?: "image",
                    mimeType = mime,
                )
            }
        }
        return out
    }

    /** Downscaled-decode and score; null when the image is undecodable or tiny (icons). */
    private fun scoreOf(uri: Uri): Double? = scoreStream(classifier) { appContext.contentResolver.openInputStream(uri) }

    // -- Upload (queued, best effort) --

    /**
     * @param uploaded reached the server in this pass.
     * @param remaining still queued afterwards (time-box hit or aborted).
     * @param stoppedOnError true when a network/server error ended the pass – chaining another pass right away
     *   would just fail again; false when only the time-box stopped it (a follow-up worker should continue).
     */
    data class DrainOutcome(val uploaded: Int, val remaining: Int, val stoppedOnError: Boolean)

    /** Queue size for follow-up scheduling; cheap COUNT query. */
    suspend fun pendingUploadCount(): Int = withContext(Dispatchers.IO) { dao.pendingUploadCount() }

    /**
     * Uploads the queue until it is empty, the server complains, or [MAX_UPLOAD_MILLIS] is up. The time-box
     * exists because a worker has a ~10-minute execution limit (a scan may have eaten into it already):
     * stopping early lets the caller chain a follow-up worker with a fresh window instead of being killed
     * mid-upload. A permanently rejected image (HTTP 4xx, e.g. too large for the server) is dropped from the
     * queue – retrying it would never succeed and it would block everything behind it.
     *
     * Every item is content-hashed while being staged; a by-hash lookup skips bodies the server already
     * has, and a failed upload gets one re-check before giving up (the outcome of an upload that died
     * mid-response is ambiguous, and the server stores idempotently by content hash).
     */
    suspend fun drainUploads(): DrainOutcome = withContext(Dispatchers.IO) {
        val client = try {
            api() ?: return@withContext DrainOutcome(0, dao.pendingUploadCount(), stoppedOnError = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext DrainOutcome(0, dao.pendingUploadCount(), stoppedOnError = true)
        }
        var uploaded = 0
        var stoppedOnError = false
        val deadline = System.currentTimeMillis() + MAX_UPLOAD_MILLIS
        for (pending in dao.pendingUploads()) {
            if (System.currentTimeMillis() >= deadline) break
            val tmp = File(appContext.cacheDir, "vault-upload-${UUID.randomUUID()}")
            try {
                val hash = copyHashing(Uri.parse(pending.uri), tmp)
                if (hash == null) {
                    // The source vanished from the device: drop it from the queue instead of retrying forever.
                    dao.markUploaded(pending.uri, System.currentTimeMillis())
                    continue
                }
                // Cheap pre-check: an earlier attempt may already have stored these bytes while its response
                // got lost on a slow relay – then the body does not need to crawl up again.
                if (client.vaultImageByHash(hash) != null) {
                    dao.markUploaded(pending.uri, System.currentTimeMillis())
                    uploaded++
                    continue
                }
                try {
                    client.uploadVaultImage(tmp, pending.fileName, pending.mimeType, pending.score)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Permanent rejections are handled below; everything else has an ambiguous outcome:
                    // the server stores idempotently, so one cheap re-check settles whether the body
                    // arrived before the connection died. Only a real miss rethrows.
                    if (e is de.wanderlogplus.client.ApiException && e.kind == de.wanderlogplus.client.ApiErrorKind.BAD_REQUEST) throw e
                    if (client.vaultImageByHash(hash) == null) throw e
                }
                dao.markUploaded(pending.uri, System.currentTimeMillis())
                uploaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: de.wanderlogplus.client.ApiException) {
                if (e.kind == de.wanderlogplus.client.ApiErrorKind.BAD_REQUEST) {
                    // Permanent rejection (too large, bad input): drop it, or it blocks the queue on every run.
                    dao.markUploaded(pending.uri, System.currentTimeMillis())
                    continue
                }
                stoppedOnError = true
                break
            } catch (e: Exception) {
                // Network or server trouble: stop here; the rest stays queued for the next run.
                stoppedOnError = true
                break
            } finally {
                tmp.delete()
            }
        }
        DrainOutcome(uploaded, dao.pendingUploadCount(), stoppedOnError)
    }

    /** Copies [uri] to [dest] while computing its SHA-256 (hex); null when the source cannot be opened. */
    private fun copyHashing(uri: Uri, dest: File): String? {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val input = openStream(uri) ?: return null
        input.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val r = inp.read(buf)
                    if (r < 0) break
                    digest.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // -- Progress --

    /** Set once a report fails: intermediate updates stop (a connect timeout would otherwise stall the scan). */
    @Volatile
    private var serverUnreachable = false

    private suspend fun publish(
        state: String,
        total: Int,
        scanned: Int,
        found: Int,
        galleryTotal: Int,
        scored: Int,
        decodeFailed: Int,
        maxScore: Double,
    ) {
        dao.upsertScanState(VaultScanStateEntity(1, state, total, scanned, found, System.currentTimeMillis(), galleryTotal, scored, decodeFailed, maxScore))
        // Intermediate reports are skipped while the server is down; the final state always gets one attempt.
        if (!serverUnreachable || state != VaultScanStates.RUNNING) report(state, total, scanned, found)
    }

    /** Every image MediaStore exposes (any format); limited by the granted photo access. */
    private fun countAllImages(): Int = try {
        appContext.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Fire-and-forget: any failure (offline, auth, server) is swallowed by design. */
    private suspend fun report(state: String, total: Int, scanned: Int, found: Int) {
        val client = try {
            api() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        try {
            client.postVaultScan(VaultScanUpdate(state, total, scanned, found, device))
            serverUnreachable = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Server unreachable: the report is skipped, the scan is unaffected.
            serverUnreachable = true
        }
    }

    companion object {
        /** Decode target for the classifier (longest side in px). */
        const val SAMPLE_SIDE = 224

        /** Images smaller than this (longest side) are icons/thumbnails, not photos. */
        const val MIN_SIDE = 64

        /** Room/server progress cadence (every Nth image). */
        const val PROGRESS_EVERY = 5

        /** Max time spent uploading per worker run; a chained follow-up gets a fresh ~10-minute window. */
        const val MAX_UPLOAD_MILLIS = 3 * 60 * 1000L

        /**
         * Downscaled-decode (~[SAMPLE_SIDE] px on the longest side) plus classifier score; null when undecodable
         * or tiny. In the companion (with a stream factory) so unit tests can run the real decode path.
         */
        internal fun scoreStream(classifier: SensitiveClassifier = SkinRatioClassifier(), openStream: () -> java.io.InputStream?): Double? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val probe = openStream() ?: return null
            // decodeStream with inJustDecodeBounds returns null BY DESIGN (it only fills bounds):
            // keep it out of any elvis chain, or every single image scores null.
            probe.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest < MIN_SIDE) return null
            var sample = 1
            while (longest / (sample * 2) >= SAMPLE_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = openStream()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) return null
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                return classifier.score(pixels, w, h)
            } finally {
                bitmap.recycle()
            }
        }

        /**
         * HEIC/HEIF is the default photo format on many phones (Samsung & co.) – without it whole galleries were
         * skipped. Formats a device cannot decode (e.g. HEIC below API 28, AVIF below 31) simply score null.
         */
        val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")
    }
}
