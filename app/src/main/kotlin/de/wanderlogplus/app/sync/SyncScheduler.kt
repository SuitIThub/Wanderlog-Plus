package de.wanderlogplus.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Plans background syncs. WorkManager is looked up lazily so construction never touches it. */
class SyncScheduler(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val work get() = WorkManager.getInstance(appContext)

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Debounced push after a local edit: a newer request replaces the pending one. */
    fun requestSoon() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    fun requestNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // KEEP: pull-to-refresh must not cancel an in-flight sync.
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val PERIODIC = "sync-periodic"
        const val SOON = "sync-soon"
        const val NOW = "sync-now"
        const val BACKOFF_SECONDS = 30L
    }
}
