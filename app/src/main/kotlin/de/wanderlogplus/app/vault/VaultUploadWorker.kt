package de.wanderlogplus.app.vault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.wanderlogplus.app.WanderlogPlusApp

/**
 * Drains the vault upload queue without scanning. Chains itself (via
 * [de.wanderlogplus.app.sync.SyncScheduler.requestVaultUploads]) when only the time-box stopped the pass –
 * a network/server error instead returns [Result.retry] so WorkManager's backoff rides out the outage
 * (a stalled upload alone can block for minutes through its request timeout). After [MAX_ERROR_RETRIES]
 * attempts it gives up; the next scan or app start resumes the queue.
 */
class VaultUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WanderlogPlusApp).container
        val outcome = container.vaultScanner.drainUploads()
        return when {
            outcome.remaining == 0 -> Result.success()
            !outcome.stoppedOnError -> {
                container.syncScheduler.requestVaultUploads()
                Result.success()
            }
            runAttemptCount < MAX_ERROR_RETRIES -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        /** With 30 s exponential backoff this spans roughly half an hour of retries before giving up. */
        const val MAX_ERROR_RETRIES = 6
    }
}
