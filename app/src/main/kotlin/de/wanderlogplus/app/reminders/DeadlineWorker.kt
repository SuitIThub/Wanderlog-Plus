package de.wanderlogplus.app.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.wanderlogplus.app.R
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.data.now
import de.wanderlogplus.app.ui.MainActivity
import de.wanderlogplus.client.TripSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What a reminder run needs; injectable so tests do not need the real container. */
class ReminderInput(val enabled: Boolean, val snapshot: TripSnapshot, val today: LocalDate)

class DeadlineWorker(
    context: Context,
    params: WorkerParameters,
    private val load: suspend () -> ReminderInput,
) : CoroutineWorker(context, params) {

    /** Used by WorkManager's default factory. */
    constructor(context: Context, params: WorkerParameters) : this(context, params, { loadFromContainer(context) })

    override suspend fun doWork(): Result {
        val input = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.success() // next daily run tries again
        }
        if (!input.enabled || !canNotify(applicationContext)) return Result.success()
        val prefs = applicationContext.getSharedPreferences(de.wanderlogplus.app.AppProfile.scoped(applicationContext, PREFS), Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(SENT, emptySet()).orEmpty().toSet()
        val due = dueReminders(input.snapshot.stays, input.snapshot.places, input.today, sent)
        if (due.isEmpty()) return Result.success()
        ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val nm = NotificationManagerCompat.from(applicationContext)
        val posted = mutableSetOf<String>()
        for (r in due) {
            val n = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(r.title)
                .setContentText(r.text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                @Suppress("MissingPermission") // checked in canNotify
                nm.notify(r.key.hashCode(), n)
                posted += r.key
            } catch (_: SecurityException) {
                break
            }
        }
        if (posted.isNotEmpty()) prefs.edit().putStringSet(SENT, sent + posted).apply()
        return Result.success()
    }

    companion object {
        const val CHANNEL = "deadlines"
        const val PREFS = "reminders"
        const val SENT = "sent"

        internal fun canNotify(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fristen", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private suspend fun loadFromContainer(context: Context): ReminderInput {
            val container = (context.applicationContext as WanderlogPlusApp).container
            container.ready.await()
            val snapshot = withContext(Dispatchers.IO) { TripSnapshot.from(container.store.all()) }
            val zone = container.settings.tripZone.first()
            return ReminderInput(container.settings.remindersEnabled.first(), snapshot, zone.now().toLocalDate())
        }
    }
}
