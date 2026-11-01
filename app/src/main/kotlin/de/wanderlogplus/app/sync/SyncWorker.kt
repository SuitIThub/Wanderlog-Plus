package de.wanderlogplus.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.SyncResult

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WanderlogPlusApp).container
        return when (val r = container.syncRunner.runOnce()) {
            is SyncResult.Failed -> when (r.kind) {
                ApiErrorKind.NETWORK, ApiErrorKind.SERVER -> Result.retry()
                else -> Result.success() // The error is shown in the sync status; retrying would not help.
            }
            else -> Result.success()
        }
    }
}
