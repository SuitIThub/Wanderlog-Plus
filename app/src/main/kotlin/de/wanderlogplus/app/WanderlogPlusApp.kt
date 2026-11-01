package de.wanderlogplus.app

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WanderlogPlusApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        container.scope.launch {
            if (container.settings.server.first() != null) {
                container.syncScheduler.schedulePeriodic()
                container.syncScheduler.requestNow()
            }
        }
    }
}
