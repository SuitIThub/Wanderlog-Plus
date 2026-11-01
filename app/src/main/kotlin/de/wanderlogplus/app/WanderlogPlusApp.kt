package de.wanderlogplus.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WanderlogPlusApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        // A throw here (e.g. WorkManager unavailable) must never crash the process; log the class name only.
        val handler = CoroutineExceptionHandler { _, e -> Log.w("WanderlogPlusApp", "Startup sync scheduling failed: ${e.javaClass.simpleName}") }
        container.scope.launch(handler) { container.syncScheduler.scheduleReminders() }
        container.scope.launch(handler) {
            if (container.settings.server.first() != null) {
                container.syncScheduler.schedulePeriodic()
                container.syncScheduler.requestNow()
            }
        }
    }
}
