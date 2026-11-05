package de.wanderlogplus.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // Automatic daily media scan (Mehr → Scan shows its state); a no-op without the media permission.
        // Plus one catch-up run right away when no scan has ever happened: the periodic registration alone would
        // wait up to a full day for its first execution, looking like "auto-scan does not run".
        container.scope.launch(handler) {
            container.syncScheduler.scheduleVaultScan()
            val neverScanned = withContext(Dispatchers.IO) { container.db.vault().scanState() == null }
            if (neverScanned) container.syncScheduler.requestVaultScan()
            // Resume a possibly interrupted upload backlog (e.g. the worker window ended mid-queue); no-op when empty.
            if (container.vaultScanner.queuedWorkCount() > 0) {
                container.syncScheduler.requestVaultUploads()
            }
        }
        container.scope.launch(handler) {
            if (container.settings.server.first() != null) {
                container.syncScheduler.schedulePeriodic()
                container.syncScheduler.requestNow()
            }
        }
    }
}
