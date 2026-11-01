package de.wanderlogplus.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun nodeIdStableAcrossInstances() {
        val a = SettingsStore(ctx).also { it.nodeId }.nodeId
        val b = SettingsStore(ctx).nodeId
        assertEquals(a, b)
        assertTrue(a!!.startsWith("dev-"))
        assertEquals(16, a.length)
        assertTrue(a.removePrefix("dev-").all { it in "0123456789abcdef" })
    }

    @Test fun syncStateRoundtrip() {
        val s = SettingsStore(ctx)
        s.since = 42; s.epoch = "e1"; s.lastSyncAt = 99L; s.lastError = "boom"; s.pendingReset = true
        val t = SettingsStore(ctx)
        assertEquals(42L, t.since)
        assertEquals("e1", t.epoch)
        assertEquals(99L, t.lastSyncAt)
        assertEquals("boom", t.lastError)
        assertTrue(t.pendingReset)
        t.lastSyncAt = null; t.lastError = null; t.epoch = null
        assertNull(SettingsStore(ctx).lastSyncAt)
        assertNull(SettingsStore(ctx).epoch)
    }

    @Test fun saveServerEmitsValue() = runTest {
        val s = SettingsStore(ctx)
        assertNull(s.server.first())
        s.saveServer(ServerSettings("http://192.168.1.2:8080", "tok"))
        assertEquals(ServerSettings("http://192.168.1.2:8080", "tok"), s.server.first())
    }

    @Test fun tripZoneDefaultsToJapanAndPersists() = runTest {
        val s = SettingsStore(ctx)
        assertEquals(TripZone.JAPAN, s.tripZone.first())
        s.setTripZone(TripZone.DEVICE)
        assertEquals(TripZone.DEVICE, SettingsStore(ctx).tripZone.first())
    }
}
