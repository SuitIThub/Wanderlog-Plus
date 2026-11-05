package de.wanderlogplus.app.vault

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import de.wanderlogplus.app.data.VaultDao
import de.wanderlogplus.app.data.VaultFoundEntity
import de.wanderlogplus.app.data.VaultScanStateEntity
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.VAULT_PART_SIZE
import de.wanderlogplus.shared.sync.VaultKind
import de.wanderlogplus.shared.sync.VaultScanStates
import de.wanderlogplus.shared.sync.VaultScanUpdate
import de.wanderlogplus.shared.sync.VaultVideoSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Walks the device's photos and videos and queues every not-yet-seen one for upload to the vault.
 * Photos go up as-is; videos contribute a JPEG thumbnail (the full file follows only when the web UI
 * queues it). Already queued/transferred items are skipped via the local table. Every server call is
 * best effort: an unreachable server never fails the pass, it only skips the report or leaves uploads
 * queued for the next run. Cancellation is the only exception that escapes [run].
 */
class VaultScanner(
    private val appContext: Context,
    private val dao: VaultDao,
    private val api: suspend () -> SyncApi?,
    private val device: String = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
    /** Reads a media item; injectable so tests can skip the content resolver. */
    private val openStream: (Uri) -> java.io.InputStream? = { appContext.contentResolver.openInputStream(it) },
    private val thumbnails: VideoThumbnails = MediaStoreVideoThumbnails(appContext),
    /** Whole-file length for a video session; never MediaStore's SIZE column (it goes stale). */
    private val fileLength: (Uri) -> Long? = { uri ->
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd -> fd.length.takeIf { it > 0 } }
    },
    private val partSize: Long = VAULT_PART_SIZE,
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
        var photosOnDevice = 0
        var videosOnDevice = 0

        suspend fun publish(state: String) {
            publish(state, total, scanned, found, galleryTotal, photosOnDevice, videosOnDevice)
        }

        try {
            val known = dao.foundUris().toHashSet()
            galleryTotal = countAllImages()
            val photos = queryImages()
            val videos = if (hasVideoPermission()) queryVideos() else emptyList()
            photosOnDevice = photos.size
            videosOnDevice = videos.size
            val items = photos + videos
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
                            kind = item.kind,
                            durationMs = item.durationMs,
                            sizeBytes = item.sizeBytes,
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

    /**
     * Videos are best-effort: images-only permission must not block the photo backup. On API 33+ this is a
     * separate grant (`READ_MEDIA_VIDEO`); below that, storage access covers both.
     */
    private fun hasVideoPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return hasMediaPermission()
        if (androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 &&
            androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private data class MediaItem(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val kind: String = "photo",
        val durationMs: Long? = null,
        val sizeBytes: Long? = null,
    )

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

    private val videoCollection: Uri
        get() = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun queryVideos(): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val out = ArrayList<MediaItem>()
        appContext.contentResolver.query(videoCollection, projection, null, null, MediaStore.Video.Media._ID)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                out += MediaItem(
                    uri = ContentUris.withAppendedId(videoCollection, c.getLong(idCol)),
                    fileName = c.getString(nameCol)?.take(200)?.ifBlank { "video" } ?: "video",
                    mimeType = c.getString(mimeCol)?.take(100)?.ifBlank { "video/mp4" } ?: "video/mp4",
                    kind = "video",
                    durationMs = c.getLong(durCol).takeIf { it > 0 },
                    sizeBytes = c.getLong(sizeCol).takeIf { it > 0 },
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
                val video = pending.kind == "video"
                val hash = if (video) {
                    val bytes = thumbnails.thumbnail(Uri.parse(pending.uri))
                    if (bytes == null) null else stageBytes(bytes, tmp)
                } else {
                    copyHashing(Uri.parse(pending.uri), tmp)
                }
                if (hash == null) {
                    // The source vanished from the device (or the thumbnail could not be decoded): drop it.
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
                    if (video) {
                        client.uploadVaultImage(
                            tmp, pending.fileName, "image/jpeg", pending.score,
                            kind = VaultKind.VIDEO,
                            videoRef = pending.uri,
                            durationMs = pending.durationMs,
                            videoBytes = pending.sizeBytes,
                        )
                    } else {
                        client.uploadVaultImage(tmp, pending.fileName, pending.mimeType, pending.score)
                    }
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
        if (!stoppedOnError) {
            for (video in dao.requestedVideos()) {
                if (System.currentTimeMillis() >= deadline) break
                when (drainFullVideo(client, video, deadline)) {
                    VideoDrain.COMPLETED -> {
                        uploaded++
                        break // at most one video per pass
                    }
                    VideoDrain.PAUSED -> break
                    VideoDrain.FAILED -> {
                        stoppedOnError = true
                        break
                    }
                    VideoDrain.VANISHED -> { /* try the next requested video */ }
                }
            }
        }
        DrainOutcome(uploaded, dao.pendingUploadCount(), stoppedOnError)
    }

    private enum class VideoDrain { COMPLETED, PAUSED, FAILED, VANISHED }

    /**
     * Chunked upload of one requested video. Pause is checked before every part; a vanished source is
     * unrequested so the next sync does not put it back.
     */
    private suspend fun drainFullVideo(client: SyncApi, pending: VaultFoundEntity, deadline: Long): VideoDrain {
        val uri = Uri.parse(pending.uri)
        val size = fileLength(uri)
        val hash = if (size != null) sha256Streaming(uri) else null
        if (size == null || hash == null) {
            vanishVideo(client, pending.uri)
            return VideoDrain.VANISHED
        }
        var session: VaultVideoSession
        try {
            session = client.startVaultVideoSession(pending.uri, pending.fileName, size, hash, pending.mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return VideoDrain.FAILED
        }
        if (session.complete) {
            dao.deleteVideoRequest(pending.uri)
            return VideoDrain.COMPLETED
        }
        val partCount = ((size + partSize - 1) / partSize).toInt()
        val received = session.receivedParts.toMutableSet()
        var i = 0
        while (i < partCount) {
            if (System.currentTimeMillis() >= deadline) return VideoDrain.PAUSED
            val paused = try {
                client.vaultUploadTrigger().paused
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return VideoDrain.FAILED
            }
            if (paused) return VideoDrain.PAUSED
            if (i in received) {
                i++
                continue
            }
            val bytes = readPart(uri, i, size)
            if (bytes == null) {
                vanishVideo(client, pending.uri)
                return VideoDrain.VANISHED
            }
            val partHash = sha256Bytes(bytes)
            var attempts = 0
            while (true) {
                try {
                    client.uploadVaultVideoPart(session.id, i, bytes, partHash)
                    received += i
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.status == 404) {
                        session = try {
                            client.startVaultVideoSession(pending.uri, pending.fileName, size, hash, pending.mimeType)
                        } catch (e2: CancellationException) {
                            throw e2
                        } catch (e2: Exception) {
                            return VideoDrain.FAILED
                        }
                        if (session.complete) {
                            dao.deleteVideoRequest(pending.uri)
                            return VideoDrain.COMPLETED
                        }
                        received.clear()
                        received.addAll(session.receivedParts)
                        break // retry this index against the new session
                    }
                    if (e.status == 409 && attempts == 0) {
                        attempts++
                        continue
                    }
                    return VideoDrain.FAILED
                } catch (e: Exception) {
                    return VideoDrain.FAILED
                }
            }
            if (i in received) i++
        }
        try {
            client.completeVaultVideoSession(session.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return VideoDrain.FAILED
        }
        dao.deleteVideoRequest(pending.uri)
        return VideoDrain.COMPLETED
    }

    private suspend fun vanishVideo(client: SyncApi, ref: String) {
        try {
            client.unrequestVaultVideo(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the local row is dropped either way so this pass does not retry a missing file.
        }
        dao.deleteVideoRequest(ref)
    }

    private fun sha256Streaming(uri: Uri): String? {
        val input = openStream(uri) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        input.use {
            val buf = ByteArray(8192)
            while (true) {
                val r = it.read(buf)
                if (r < 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readPart(uri: Uri, index: Int, totalSize: Long): ByteArray? {
        val start = index * partSize
        val len = minOf(partSize, totalSize - start).toInt()
        if (len <= 0) return ByteArray(0)
        val input = openStream(uri) ?: return null
        input.use {
            var skipped = 0L
            while (skipped < start) {
                val n = it.skip(start - skipped)
                if (n > 0) {
                    skipped += n
                    continue
                }
                val dump = ByteArray(8192)
                val r = it.read(dump, 0, minOf(dump.size.toLong(), start - skipped).toInt())
                if (r < 0) return null
                skipped += r
            }
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = it.read(buf, off, len - off)
                if (r < 0) return null
                off += r
            }
            return buf
        }
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

    /** Writes [bytes] to [dest] and returns their SHA-256 hex (same digest the by-hash pre-check uses). */
    private fun stageBytes(bytes: ByteArray, dest: File): String {
        dest.writeBytes(bytes)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
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
        photosOnDevice: Int,
        videosOnDevice: Int,
    ) {
        dao.upsertScanState(VaultScanStateEntity(1, state, total, scanned, found, System.currentTimeMillis(), galleryTotal))
        // Intermediate reports are skipped while the server is down; the final state always gets one attempt.
        if (!serverUnreachable || state != VaultScanStates.RUNNING) report(state, total, scanned, found, photosOnDevice, videosOnDevice)
    }

    /** Every image MediaStore exposes (any format); limited by the granted photo access. */
    private fun countAllImages(): Int = try {
        appContext.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Fire-and-forget: any failure (offline, auth, server) is swallowed by design. */
    private suspend fun report(state: String, total: Int, scanned: Int, found: Int, photosOnDevice: Int, videosOnDevice: Int) {
        val client = try {
            api() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        try {
            client.postVaultScan(
                VaultScanUpdate(
                    state, total, scanned, found, device,
                    photosOnDevice = photosOnDevice,
                    videosOnDevice = videosOnDevice,
                    pendingUploads = dao.pendingUploadCount(),
                )
            )
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
