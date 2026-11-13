package de.wanderlogplus.app.widgets

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.wanderlogplus.app.AppContainer
import de.wanderlogplus.app.AppProfile
import de.wanderlogplus.app.WanderlogPlusApp
import de.wanderlogplus.app.data.now
import de.wanderlogplus.client.TripSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** All trip widgets, for redrawing them together. */
fun allTripWidgets(): List<TripWidget> =
    listOf(NextUpWidget(), NextRideWidget(), TodayWidget(), StayWidget(), BudgetWidget(), WeatherWidget(), ChatWidget())

/** Redraws every placed trip widget. */
fun androidRedraw(context: Context): suspend () -> Unit = { allTripWidgets().forEach { it.updateAll(context) } }

/** Plans the boundary redraw as unique one-time work (a newer plan replaces the older one). */
class WorkBoundaryScheduler(context: Context) : BoundaryScheduler {
    private val app = context.applicationContext ?: context

    override fun at(time: LocalDateTime, now: LocalDateTime) {
        // A few seconds after the boundary, so that "now" is past it when the widgets are drawn.
        val delay = Duration.between(now, time).toMillis().coerceAtLeast(0) + 5_000
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork(BOUNDARY_WORK, ExistingWorkPolicy.REPLACE, req)
    }
}

/** The 15-minute redraw (keeps countdowns and the weather fresh). */
fun scheduleWidgetRefresh(context: Context) {
    val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context.applicationContext ?: context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
}

/** Redraws the widgets; the weather is refreshed first when it is old (the repository decides, errors keep the old one). */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WanderlogPlusApp).container
        runCatching { container.weather.refresh() }
        container.widgets.updateAll()
        return Result.success()
    }
}

/** The widgets' input from the app's local data; never touches the network. */
suspend fun loadWidgetInput(context: Context, container: AppContainer): WidgetInput {
    container.ready.await()
    val snapshot = container.snapshots().snapshot.value ?: withContext(Dispatchers.IO) { TripSnapshot.from(container.store.all()) }
    val zone = container.settings.tripZone.first()
    val sim = container.settings.simulatedTime.first()?.takeIf { AppProfile.isDebug(context) }
    val now = zone.now().let { real -> sim?.at(real) ?: real }
    return WidgetInput(
        snapshot = snapshot,
        now = now,
        weather = container.weather.stored(),
        rate = container.settings.lastJpyRate.first(),
        lastChatTitle = null,
        quick = withContext(Dispatchers.IO) { quickStore(context).read() },
    )
}

/** The quick ask result file of the active profile. */
fun quickStore(context: Context) = QuickStore(File(context.filesDir, AppProfile.scoped(context, "widget-quick.json")))

const val BOUNDARY_WORK = "widget-boundary"
const val PERIODIC_WORK = "widget-refresh"
