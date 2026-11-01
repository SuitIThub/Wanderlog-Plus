package de.wanderlogplus.app.files

import de.wanderlogplus.app.data.PendingUploadDao
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Attachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [retryable] is true when at least one failure was a NETWORK or SERVER error, a 408/429 (or not an API error at all). */
data class FileSyncResult(val uploaded: Int, val downloaded: Int, val failed: Int, val retryable: Boolean = false)

/** Uploads queued attachment files, then downloads files referenced by attachments that are missing locally. */
class FileSyncRunner(
    private val api: () -> SyncApi?,
    private val files: AttachmentFiles,
    private val uploads: PendingUploadDao,
    private val attachments: suspend () -> List<Attachment>,
) {
    suspend fun run(): FileSyncResult = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext FileSyncResult(0, 0, 0)
        files.cleanStale()
        var uploaded = 0
        var downloaded = 0
        var failed = 0
        var retryable = false
        fun fail(e: Exception) {
            failed++
            if (e !is ApiException || e.kind == ApiErrorKind.NETWORK || e.kind == ApiErrorKind.SERVER || e.status in RETRYABLE_STATUS) {
                retryable = true
            }
        }

        for (p in uploads.all()) {
            val file = files.fileFor(p.hash)
            if (file == null) {
                uploads.delete(p.hash)
                failed++
                continue
            }
            try {
                api.uploadFile(file, p.fileName, p.mimeType)
                uploads.delete(p.hash)
                uploaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the server's own verdict drops the file; other 4xx (e.g. a proxy's 413 without a body) stay queued.
                if (e is ApiException && e.code == "file_too_large") uploads.delete(p.hash)
                fail(e)
            }
        }

        val missing = attachments().map { it.fileHash }.filter { HASH.matches(it) }.distinct().filter { files.fileFor(it) == null }
        for (hash in missing) {
            try {
                api.downloadFile(hash, files.target(hash))
                files.notifyAdded()
                downloaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
        FileSyncResult(uploaded, downloaded, failed, retryable)
    }

    private companion object {
        /** Request timeout and rate limit: worth retrying although they are 4xx. */
        val RETRYABLE_STATUS = setOf(408, 429)

        /** Hashes that cannot name a file are skipped (neither failed nor retryable). */
        val HASH = Regex("[0-9a-f]{64}")
    }
}
