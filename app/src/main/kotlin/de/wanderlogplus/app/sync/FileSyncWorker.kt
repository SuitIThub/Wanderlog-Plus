package de.wanderlogplus.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.files.FileSyncResult

class FileSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WanderlogPlusApp).container
        return resultFor(container.fileSyncRunner.run())
    }

    companion object {
        /** Only network/server trouble is worth a backoff retry; a 404 alone is retried by the next sync's run. */
        internal fun resultFor(r: FileSyncResult): Result =
            if (r.failed > 0 && r.retryable) Result.retry() else Result.success()
    }
}
