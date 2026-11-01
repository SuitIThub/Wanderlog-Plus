package de.wanderlogplus.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Application startup must not crash, even when scheduling fails (WorkManager unavailable in tests). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WanderlogPlusApp::class)
class WanderlogPlusAppTest {
    @Test fun startupDoesNotCrash() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<WanderlogPlusApp>()
        assertNotNull(app.container)
        // Another test in this JVM may have left a server in the DataStore; scheduling must not crash then either.
        app.container.settings.server.first()
        Unit
    }
}
