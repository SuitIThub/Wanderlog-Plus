package de.wanderlogplus.app.vault

import android.content.ContentUris
import android.content.Context
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
 * Walks the device's images and queues every not-yet-seen one for upload to the vault (no classification:
 * the vault is a plain full backup, already queued/transferred images are skipped via the local table).
 * Every server call is best effort: an unreachable server never fails the pass, it only skips the report
 * or leaves uploads queued for the next run. Cancellation is the only exception that escapes [run].
 */
class VaultScanner(
    private val appContext: Context,
    private val dao: VaultDao,
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

        suspend fun publish(state: String) {
            publish(state, total, scanned, found, galleryTotal)
        }

        try {
            val known = dao.foundUris().toHashSet()
            galleryTotal = countAllImages()
            val items = queryImages()
            total = items.size
            publish(VaultScanStates.RUNNING)
            for (item in items) {
                if (item.uri.toString() !in known) {
                    dao.insertFound(
                        VaultFoundEntity(
                            uri = item.uri.toString(),
                            fileName = item.fileName,
                            mimeType = item.mimeType,
                            score = 0.0,
                            foundAt = System.currentTimeMillis(),
                            uploadedAt = null,
                        )
                    )
                    found++
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
                out += MediaItem(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    fileName = c.getString(nameCol)?.take(200)?.ifBlank { "image" } ?: "image",
                    // Rarely null in MediaStore; the server insists on an image/* type.
                    mimeType = c.getString(mimeCol)?.take(100)?.ifBlank { "image/jpeg" } ?: "image/jpeg",
                )
            }
        }
        return out
    }

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
    ) {
        dao.upsertScanState(VaultScanStateEntity(1, state, total, scanned, found, System.currentTimeMillis(), galleryTotal))
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
        /** Room/server progress cadence (every Nth image). */
        const val PROGRESS_EVERY = 5

        /** Max time spent uploading per worker run; a chained follow-up gets a fresh ~10-minute window. */
        const val MAX_UPLOAD_MILLIS = 3 * 60 * 1000L
    }
}
