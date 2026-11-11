package de.wanderlogplus.app.ui.reels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.wanderlogplus.app.R
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.reminders.DeadlineWorker
import de.wanderlogplus.app.ui.EXTRA_REEL_JOB
import de.wanderlogplus.app.ui.MainActivity
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.shared.sync.AiJobInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** What the notification for a finished reel job says (fixed German text, no server data except counts). */
fun reelNotificationText(job: AiJobInfo): Pair<String, String> = when {
    job.status == "FAILED" -> "Reel-Auswertung fehlgeschlagen" to errorText(job.error)
    job.candidates.isEmpty() && job.tips.isNotEmpty() ->
        "Beitrag ausgewertet" to (if (job.tips.size == 1) "1 Tipp gefunden und geprüft. Tippen zum Ansehen." else "${job.tips.size} Tipps gefunden und geprüft. Tippen zum Ansehen.")
    job.candidates.isEmpty() -> "Reel ausgewertet" to "Im Reel wurde kein konkreter Ort erkannt."
    else -> {
        val onRoute = job.candidates.count { it.onRoute }
        val n = job.candidates.size
        (if (n == 1) "1 Ort im Reel gefunden" else "$n Orte im Reel gefunden") to
            (if (onRoute == n) "Alle liegen auf eurer Route. Tippen zum Ansehen." else "$onRoute davon auf eurer Route. Tippen zum Ansehen.")
    }
}

/** Looks up a job on the server; null without server. Replaced in tests. */
fun interface JobLookup {
    suspend fun job(id: String): AiJobInfo?
}

/**
 * Waits in the background for a reel job on the server and posts a notification when it is done (or failed); a tap
 * opens the result. Each run polls for a few minutes; a job still running then is retried by WorkManager with backoff.
 */
class ReelWatchWorker(
    context: Context,
    params: WorkerParameters,
    private val lookup: JobLookup,
    private val pollMillis: Long = POLL_MILLIS,
    private val attempts: Int = ATTEMPTS_PER_RUN,
) : CoroutineWorker(context, params) {

    /** Used by WorkManager's default factory. */
    constructor(context: Context, params: WorkerParameters) : this(
        context, params,
        JobLookup { id -> (context.applicationContext as WanderlogPlusApp).container.syncApi()?.aiJob(id) },
    )

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_JOB) ?: return Result.failure()
        repeat(attempts) { i ->
            val job = try {
                lookup.job(id) ?: return Result.failure() // server removed from settings
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.kind != ApiErrorKind.NETWORK) return Result.failure()
                null
            }
            if (job != null && isFinished(job)) {
                notifyDone(applicationContext, job)
                return Result.success()
            }
            if (i < attempts - 1) delay(pollMillis)
        }
        // Still running (or offline): WorkManager tries again later.
        return if (runAttemptCount < MAX_RUNS) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_JOB = "jobId"
        const val CHANNEL = "reels"
        private const val POLL_MILLIS = 15_000L
        private const val ATTEMPTS_PER_RUN = 32 // ~8 minutes, below WorkManager's 10-minute limit
        private const val MAX_RUNS = 12

        /** Starts watching [jobId] (once per job). */
        fun enqueue(context: Context, jobId: String) {
            val request = OneTimeWorkRequestBuilder<ReelWatchWorker>()
                .setInputData(workDataOf(KEY_JOB to jobId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("reel-$jobId", ExistingWorkPolicy.KEEP, request)
        }

        /** The notification for a finished job; a tap opens its result in the app. */
        fun notifyDone(context: Context, job: AiJobInfo) {
            if (!DeadlineWorker.canNotify(context)) return
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(CHANNEL, "Reel-Auswertungen", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                context,
                job.id.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_REEL_JOB, job.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val (title, text) = reelNotificationText(job)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                @Suppress("MissingPermission") // checked in canNotify
                NotificationManagerCompat.from(context).notify(job.id.hashCode(), n)
            } catch (_: SecurityException) {
                // Notifications were switched off meanwhile; the result stays reachable via Orte → Reels.
            }
        }
    }
}
