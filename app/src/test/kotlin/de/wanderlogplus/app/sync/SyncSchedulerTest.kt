package de.wanderlogplus.app.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
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
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
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

    @Test fun requestNowEnqueuesUniqueWork() {
        scheduler.requestNow()
        val first = infos("sync-now").single().id
        scheduler.requestNow()
        assertEquals(listOf(first), infos("sync-now").map { it.id }) // KEEP: second request does not replace
    }

    @Test fun schedulePeriodicIsFifteenMinutesWithNetworkConstraint() {
        scheduler.schedulePeriodic()
        scheduler.schedulePeriodic()
        val info = infos("sync-periodic").single()
        assertEquals(TimeUnit.MINUTES.toMillis(15), info.periodicityInfo?.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test fun soonPolicyAppendsWhileRunningAndReplacesOtherwise() {
        assertEquals(androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE, SyncScheduler.soonPolicy(listOf(WorkInfo.State.RUNNING)))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, SyncScheduler.soonPolicy(listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, SyncScheduler.soonPolicy(emptyList()))
    }
}
