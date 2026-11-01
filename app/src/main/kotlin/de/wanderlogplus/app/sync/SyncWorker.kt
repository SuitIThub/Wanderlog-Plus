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
        return resultFor(container.syncRunner.runOnce())
    }

    companion object {
        /**
         * Network, server, token and protocol problems may be fixed on the server side, so they back off and retry.
         * Clock drift is shown in the sync status; retrying fast would not help until the user fixes the clock.
         */
        internal fun resultFor(r: SyncResult?): Result = when (r) {
            is SyncResult.Failed -> when (r.kind) {
                ApiErrorKind.NETWORK, ApiErrorKind.SERVER, ApiErrorKind.UNAUTHORIZED, ApiErrorKind.PROTOCOL -> Result.retry()
                else -> Result.success()
            }
            else -> Result.success()
        }
    }
}
