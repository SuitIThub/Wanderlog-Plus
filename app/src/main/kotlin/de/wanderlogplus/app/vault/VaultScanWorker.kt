package de.wanderlogplus.app.vault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.wanderlogplus.app.WanderlogPlusApp

/**
 * Runs one vault scan. Always "success": failures live in the scan state row (visible in the app and,
 * when reachable, on the server's web overview) – an unreachable server is not worth a retry loop.
 */
class VaultScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WanderlogPlusApp).container
        container.vaultScanner.run()
        // The scan may have left uploads queued (time-box): hand them to the upload chain with fresh windows.
        if (container.vaultScanner.pendingUploadCount() > 0) container.syncScheduler.requestVaultUploads()
        return Result.success()
    }
}
