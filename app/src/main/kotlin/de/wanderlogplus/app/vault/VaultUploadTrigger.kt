package de.wanderlogplus.app.vault

import de.wanderlogplus.client.SyncApi
import kotlinx.coroutines.CancellationException

/**
 * The server cannot push to the device: its web overview ("Upload fortsetzen") sets a flag instead, and the app
 * checks it here after every successful sync. At-least-once semantics: the flag is cleared only after the upload
 * pass was scheduled, so a crash in between costs one harmless extra pass (an empty queue is a no-op).
 */
class VaultUploadTrigger(
    private val api: suspend () -> SyncApi?,
    private val onRequested: () -> Unit,
) {
    /** Best effort: any failure leaves the flag (when set) for the next sync; old servers without the route are ignored. */
    suspend fun check() {
        val client = try {
            api() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        try {
            if (!client.vaultUploadRequested()) return
            onRequested()
            client.clearVaultUploadRequest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network/server trouble (or a stale server): the flag stays set, the next sync retries.
        }
    }
}
