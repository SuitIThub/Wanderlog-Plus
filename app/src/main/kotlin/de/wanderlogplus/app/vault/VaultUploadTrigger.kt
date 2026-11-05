package de.wanderlogplus.app.vault

import de.wanderlogplus.app.data.VaultDao
import de.wanderlogplus.app.data.VaultVideoRequestEntity
import de.wanderlogplus.client.SyncApi
import kotlinx.coroutines.CancellationException

/**
 * The server cannot push to the device: after every successful sync the app pulls the web overview's
 * trigger ([requested] resume-flag, sticky [paused], queued [videoRefs]). At-least-once: the resume
 * flag is cleared only after the upload pass was scheduled, so a crash in between costs one extra
 * pass. Pause is never auto-resumed. Video refs are mirrored first so a paused device still knows
 * what to upload once the web UI resumes it.
 */
class VaultUploadTrigger(
    private val api: suspend () -> SyncApi?,
    private val dao: VaultDao,
    private val onRequested: () -> Unit,
) {
    /** Best effort: any failure leaves flags and the local queue for the next sync. */
    suspend fun check() {
        val client = try {
            api() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        try {
            val t = client.vaultUploadTrigger()
            mirror(t.videoRefs)
            if (t.paused) return
            if (t.requested || dao.videoRequestRefs().isNotEmpty()) {
                onRequested()
                if (t.requested) client.clearVaultUploadRequest()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network/server trouble (or a stale server): flags stay set, the next sync retries.
        }
    }

    private fun mirror(refs: List<String>) {
        val wanted = refs.toSet()
        for (ref in wanted) dao.insertVideoRequest(VaultVideoRequestEntity(ref, System.currentTimeMillis()))
        for (have in dao.videoRequestRefs()) if (have !in wanted) dao.deleteVideoRequest(have)
    }
}
