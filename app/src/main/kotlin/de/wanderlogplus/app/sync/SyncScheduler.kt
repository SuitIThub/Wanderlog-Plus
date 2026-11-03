package de.wanderlogplus.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.wanderlogplus.app.reminders.DeadlineWorker
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

    /**
     * Debounced push after a local edit: a newer request replaces the pending one. If the sync-soon work is
     * already running it must not be cancelled (it may be mid-push), so the new request is appended after it.
     */
    fun requestSoon() {
        val states = try {
            work.getWorkInfosForUniqueWork(SOON).get().map { it.state }
        } catch (e: Exception) {
            emptyList() // unknown state: fall back to the debounce behaviour
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(SOON, soonPolicy(states), request)
    }

    /**
     * Sync on user request. A run in progress is kept (pull-to-refresh must not cancel it), but a request waiting in
     * backoff after a failure is replaced: otherwise the button does nothing for up to hours once the server is back.
     */
    fun requestNow() {
        val states = try {
            work.getWorkInfosForUniqueWork(NOW).get().map { it.state }
        } catch (e: Exception) {
            emptyList() // unknown state: a fresh request is the useful default
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(NOW, nowPolicy(states), request)
    }

    /** Uploads queued attachment files and downloads missing ones; appended only while a run is active, otherwise replaced so a backing-off chain does not grow. */
    fun requestFiles() {
        val states = try {
            work.getWorkInfosForUniqueWork(FILES).get().map { it.state }
        } catch (e: Exception) {
            emptyList()
        }
        val request = OneTimeWorkRequestBuilder<FileSyncWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(FILES, soonPolicy(states), request)
    }

    /** Daily check for upcoming cancellation deadlines; independent of any server. */
    fun scheduleReminders() {
        val request = PeriodicWorkRequestBuilder<DeadlineWorker>(1, TimeUnit.DAYS).build()
        work.enqueueUniquePeriodicWork(REMINDERS, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal companion object {
        /** APPEND_OR_REPLACE while a sync is running, REPLACE (debounce) otherwise. */
        fun soonPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy =
            if (states.any { it == WorkInfo.State.RUNNING }) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE

        /** KEEP while a sync is running, REPLACE otherwise (also a request waiting in backoff). */
        fun nowPolicy(states: List<WorkInfo.State>): ExistingWorkPolicy =
            if (states.any { it == WorkInfo.State.RUNNING }) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE

        const val PERIODIC = "sync-periodic"
        const val SOON = "sync-soon"
        const val NOW = "sync-now"
        const val FILES = "files"
        const val REMINDERS = "reminders"
        const val BACKOFF_SECONDS = 30L
    }
}
