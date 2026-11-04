package de.wanderlogplus.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: SyncScheduler

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Never run workers: keep requests ENQUEUED. The tasks are kept (not dropped) so their pending futures are never
        // finalized later in the suite, which would resume WorkManager on a closed database and fail an unrelated test.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor { parked += it }.build())
        scheduler = SyncScheduler(context)
    }

    private fun infos(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test fun requestSoonTwiceEnqueuesExactlyOneWork() {
        scheduler.requestSoon()
        scheduler.requestSoon()
        val active = infos("sync-soon").filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
        assertEquals(NetworkType.CONNECTED, active.single().constraints.requiredNetworkType)
    }

    @Test fun requestNowReplacesWaitingWork() {
        scheduler.requestNow()
        val first = infos("sync-now").single().id
        scheduler.requestNow()
        // Not running (e.g. waiting in backoff): the new request replaces it, so exactly one is waiting.
        val active = infos("sync-now").filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
        assertTrue(active.single().id != first)
    }

    @Test fun nowPolicyKeepsRunningSyncAndReplacesOtherwise() {
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, SyncScheduler.nowPolicy(listOf(WorkInfo.State.RUNNING)))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, SyncScheduler.nowPolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, SyncScheduler.nowPolicy(emptyList()))
    }

    @Test fun requestVaultUploadsEnqueuesSingleWifiOnlyWork() {
        scheduler.requestVaultUploads()
        scheduler.requestVaultUploads()
        val active = infos("vault-uploads").filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
        assertEquals(NetworkType.UNMETERED, active.single().constraints.requiredNetworkType)
    }

    @Test fun scheduleVaultScanIsDailyBatteryAwareAndOfflineCapable() {
        scheduler.scheduleVaultScan()
        scheduler.scheduleVaultScan()
        val info = infos("vault-scan-auto").single()
        assertEquals(TimeUnit.DAYS.toMillis(1), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.NOT_REQUIRED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
    }

    @Test fun schedulePeriodicIsFifteenMinutesWithNetworkConstraint() {
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        val info = infos("sync-periodic").single()
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test fun requestFilesEnqueuesUniqueWork() {
        scheduler.requestFiles()
        scheduler.requestFiles()
        val all = infos("files")
        assertEquals(1, all.filter { it.state == WorkInfo.State.ENQUEUED }.size)
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
        assertTrue(all.any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun soonPolicyAppendsWhileRunningAndReplacesOtherwise() {
        assertEquals(androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, SyncScheduler.soonPolicy(listOf(WorkInfo.State.RUNNING)))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, SyncScheduler.soonPolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, SyncScheduler.soonPolicy(emptyList()))
    }

    private companion object {
        val parked = java.util.Collections.synchronizedList(mutableListOf<Runnable>())
    }
}
